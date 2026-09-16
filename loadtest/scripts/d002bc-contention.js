// D-002c — 비동기 재고 예약 **락 경합** (계획서 docs/plans/task-d002-bc-session.md P8~P10)
//
// **응답 p95 는 이 축의 지표가 아니다.** 분산 락은 동기 HTTP 경로를 떠났다(계획서 §2 V4):
//   StockReservationConsumer.handleOrderCreated(@Transactional)
//     → StockReservationService.reserve
//       → InventoryLockFacade.decreaseStock   ← main 코드의 유일한 호출자
// 락 획득 실패(PRD-004)는 사용자 응답이 아니라 **consumer 예외**로 나타나 재시도/DLQ 로 흡수된다.
// 따라서 부하 클라이언트가 보는 지연은 경합과 거의 무관하다 — k6 는 **경합을 만들기만** 하고,
// 관측은 전부 서버 쪽에서 한다(consumer lag · 재시도 · DLQ · 예약 원장 · saga 메트릭).
//
// 두 모드를 같은 부하량으로 돌린다(계획서 §4 P8/P9 대조군):
//   MODE=contend  — 전 주문이 **상품 1개**에 몰린다. 경합 최대.
//   MODE=spread   — 주문이 PRODUCT_IDS 전체로 흩어진다. 경합 최소. **대조군.**
// 대조군에서도 충돌 지표가 나오면 그것은 경합이 아니라 다른 원인이다.
//
// 실행:
//   k6 run -e GW=http://<gateway-lb>:8080 -e PRODUCT_IDS=1,2,3,4,5 -e USERS=200 \
//     -e MODE=contend -e RPS=1000 loadtest/scripts/d002bc-contention.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

const GW = __ENV.GW;
const USERS = parseInt(__ENV.USERS || '200');
const RPS = parseInt(__ENV.RPS || '1000');
const MODE = __ENV.MODE || 'contend';
const PRODUCT_IDS = (__ENV.PRODUCT_IDS || '1').split(',').map((s) => parseInt(s.trim()));

if (MODE !== 'contend' && MODE !== 'spread') throw new Error(`MODE 는 contend|spread — 받은 값: ${MODE}`);
if (MODE === 'spread' && PRODUCT_IDS.length < 2) {
  throw new Error('spread 대조군은 상품이 2개 이상이어야 의미가 있다 — PRODUCT_IDS 를 늘려라.');
}

const RPS_CEILING = 40 * USERS;
if (RPS > RPS_CEILING) throw new Error(`RPS=${RPS} > 40×USERS=${RPS_CEILING} — 429 가 섞인다.`);

const users = new SharedArray('users', function () {
  return papaparse.parse(open('./users.csv'), { header: true, skipEmptyLines: true }).data;
});

// users.csv 가 USERS 보다 적으면 **같은 계정의 토큰을 여러 VU 가 나눠 쓰게 된다**. 그러면
// 사용자별 40 req/s 한도가 VU 수만큼 겹쳐 걸려 429 가 터지고, 부하를 사용자 수로 올린다는
// 이 측정의 전제(계획서 §2 V8)가 무너진다. 조용히 넘어가지 않고 여기서 죽인다.
//   해결: bash loadtest/scripts/generate-users-csv.sh --count <USERS>
if (users.length < USERS) {
  throw new Error(`users.csv 에 ${users.length}명뿐인데 USERS=${USERS} — 계정이 겹쳐 한도가 무너진다. `
    + `generate-users-csv.sh --count ${USERS} 로 재생성하라.`);
}

const orderCreate = new Trend('peekcart_order_create', true);
const rateLimited = new Counter('peekcart_429');
const accepted = new Counter('peekcart_order_accepted');
const rejected = new Counter('peekcart_order_rejected');

export const options = {
  scenarios: {
    contention: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: __ENV.DUR || '3m',
      preAllocatedVUs: USERS,
      maxVUs: USERS,
    },
  },
  thresholds: {
    'peekcart_429': ['count==0'],
  },
};

export function setup() {
  // b' 와 동일 — 로그인 라우트는 IP 키 10 req/s 라 순차 + 150ms 간격.
  const tokens = [];
  for (let i = 0; i < USERS; i++) {
    const u = users[i % users.length];
    let res, tries = 0;
    do {
      res = http.post(`${GW}/api/v1/auth/login`, JSON.stringify({ email: u.email, password: u.password }),
        { headers: { 'Content-Type': 'application/json' } });
      if (res.status === 429) { tries++; sleep(1); }
    } while (res.status === 429 && tries < 5);
    if (res.status !== 200) throw new Error(`setup 로그인 실패 ${u.email} ${res.status}: ${res.body}`);
    tokens.push(res.json('data.accessToken'));
    sleep(0.15);
  }
  return { tokens, startedAt: new Date().toISOString() };
}

export default function (data) {
  const idx = (__VU - 1) % data.tokens.length;
  const headers = { 'Content-Type': 'application/json', 'Authorization': `Bearer ${data.tokens[idx]}` };
  const productId = MODE === 'contend' ? PRODUCT_IDS[0] : PRODUCT_IDS[__ITER % PRODUCT_IDS.length];

  const addRes = http.post(`${GW}/api/v1/cart/items`,
    JSON.stringify({ productId, quantity: 1 }), { headers, tags: { step: 'cart_add', mode: MODE } });
  if (addRes.status === 429) { rateLimited.add(1); return; }
  if (addRes.status >= 400) { rejected.add(1); return; }

  const res = http.post(`${GW}/api/v1/orders`,
    JSON.stringify({ receiverName: 'loadtest', phone: '010-1234-5678', zipcode: '06234', address: '서울시 강남구' }),
    { headers, tags: { step: 'order_create', mode: MODE } });
  orderCreate.add(res.timings.duration);

  if (res.status === 429) rateLimited.add(1);
  else if (res.status === 201) accepted.add(1);   // 주문은 **수락**됐을 뿐 예약은 아직이다
  else rejected.add(1);

  check(res, { 'order 201': (r) => r.status === 201 });
}

export function handleSummary(data) {
  const a = (data.metrics.peekcart_order_accepted || { values: { count: 0 } }).values.count;
  return {
    stdout: `
=== D-002c ${MODE} ===
주문 수락: ${a}건. **이 숫자는 예약 성공 건수가 아니다** — 예약은 Kafka consumer 가 나중에 한다.
실제 결과는 서버에서 확인한다:
  1) consumer lag:  kafka-consumer-groups --describe --group product-svc-order-created-group
  2) 예약 원장:     loadtest/sql/d002bc-verify.sql
  3) DLQ:           SELECT COUNT(*) FROM peekcart_product.dead_letter_records;
  4) saga 메트릭:   product-service /actuator/prometheus 의 reservation_* 카운터
  5) 낙관락 충돌:   product-service 로그의 OptimisticLockingFailureException 건수
     ↳ **P9 의 핵심**: 분산 락이 커밋을 직렬화한다면 이 값은 드물어야 한다. 유의하게 나오면
       락 해제가 커밋보다 먼저 일어난다는 코드 사실(계획서 §2 V5)이 런타임에서 확인된 것이다.

`,
  };
}
