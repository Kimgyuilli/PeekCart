// D-002c — 비동기 재고 예약 **락 경합** (계획서 docs/plans/done/task-d002-bc-session.md P8~P10)
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
//   MODE=contend  — 전 주문이 **상품 1개**(PRODUCT_IDS 의 첫 값)에 몰린다. 경합 최대.
//   MODE=spread   — 주문이 PRODUCT_IDS 전체를 **전역 반복 순번으로 한 바퀴씩** 돈다. 경합 최소. **대조군.**
// 대조군에서도 충돌 지표가 나오면 그것은 경합이 아니라 다른 원인이다.
//
// run 판정(D-106 ⑤, docs/plans/D-078-remeasure-plan.md): dropped_iterations 가 목표 반복 수
// (RPS × DUR)의 1% 를 넘으면 그 run 은 부하 미달이라 **무효**다. threshold 가 실패하면 k6 는 exit 99 로
// 끝나고, 요약 첫 줄이 판정과 이유를 말한다. 무효 run 은 버리고 초기화 뒤 다시 돌린다.
//
// 실행:
//   k6 run -e GW=http://<gateway-lb>:8080 -e PRODUCT_IDS=1,2,3,4,5 -e USERS=450 \
//     -e MODE=contend -e RPS=170 -e DUR=60s loadtest/scripts/d002bc-contention.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

const GW = __ENV.GW;
const USERS = parseInt(__ENV.USERS || '200');
const RPS = parseInt(__ENV.RPS || '1000');
const MODE = __ENV.MODE || 'contend';
const DUR = __ENV.DUR || '3m';
const PRODUCT_IDS = (__ENV.PRODUCT_IDS || '1').split(',').map((s) => parseInt(s.trim()));

if (MODE !== 'contend' && MODE !== 'spread') throw new Error(`MODE 는 contend|spread — 받은 값: ${MODE}`);
if (MODE === 'spread' && PRODUCT_IDS.length < 2) {
  throw new Error('spread 대조군은 상품이 2개 이상이어야 의미가 있다 — PRODUCT_IDS 를 늘려라.');
}
// 시드 출력(상품 1,000개면 약 6KB)을 옮기다 빈 칸이나 잘린 값이 섞이면 장바구니 요청이 400 으로 흩어진다.
if (PRODUCT_IDS.some((id) => !Number.isInteger(id) || id <= 0)) {
  throw new Error('PRODUCT_IDS 에 양의 정수가 아닌 값이 있다 — d002bc-seed-products.sh 출력을 그대로 넘겨라.');
}

// 이 검사는 사용자별 한도(40 req/s)만 본다. VU 가 모자라 부하가 덜 들어가는지는 아래 dropped_iterations 판정이 본다.
const RPS_CEILING = 40 * USERS;
if (RPS > RPS_CEILING) throw new Error(`RPS=${RPS} > 40×USERS=${RPS_CEILING} — 429 가 섞인다.`);

// k6 기간 문자열을 초로 바꾼다. 판정 한도가 조용히 틀리지 않도록 정수 h·m·s 조합이 아니면 죽인다.
export function durationSeconds(text) {
  const match = /^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?$/.exec(text);
  const seconds = match ? (+(match[1] || 0)) * 3600 + (+(match[2] || 0)) * 60 + (+(match[3] || 0)) : 0;
  if (!(seconds > 0)) throw new Error(`DUR 을 해석할 수 없다: '${text}' (예: 60s, 3m, 1m30s)`);
  return seconds;
}

// USERS 산식(계획서 §2): RPS × 반복 p95(초) × 여유 1.5 를 50 단위로 올린다. constant-arrival-rate 는 반복 하나가
// VU 하나를 그 시간 동안 잡으므로 필요한 VU 는 도착률 × 반복 시간이다. 요약이 run 마다 이 값을 찍는다.
export function usersFor(rps, iterationP95Seconds) {
  return Math.ceil((rps * iterationP95Seconds * 1.5) / 50) * 50;
}

const TARGET_ITERATIONS = RPS * durationSeconds(DUR);
const MAX_DROPPED = Math.floor(TARGET_ITERATIONS / 100);

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
  // setup 은 로그인 라우트 한도(IP 10 req/s) 때문에 USERS 명을 순차로 로그인한다. 2026-10-24 세션은 200명에
  // 약 50~55초가 걸려 기본 60초에 붙어 있었다. 1명당 1초를 상한으로 둔다(넘으면 k6 exit 100).
  setupTimeout: `${Math.max(60, USERS)}s`,
  scenarios: {
    contention: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: DUR,
      preAllocatedVUs: USERS,
      maxVUs: USERS,
    },
  },
  thresholds: {
    'peekcart_429': ['count==0'],
    'dropped_iterations': [`count<=${MAX_DROPPED}`],
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
  // `__ITER` 는 VU 별 반복 번호라 VU 당 반복 수(USERS 450·RPS 170·60s 면 약 23)까지만 커진다. 그것으로 고르면
  // 상품 1,000개 중 앞쪽 수십 개만 쓰이고, run 초반에는 모든 VU 가 같은 상품을 고른다. 전역 순번을 쓴다.
  const productId = MODE === 'contend'
    ? PRODUCT_IDS[0]
    : PRODUCT_IDS[exec.scenario.iterationInTest % PRODUCT_IDS.length];

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

// 부하 판정 문장. 0건이면 k6 가 dropped_iterations 를 요약에 넣지 않으므로 없음은 0 이다.
export function loadVerdict(metrics) {
  const m = metrics.dropped_iterations;
  const dropped = (m && m.values && m.values.count) || 0;
  const valid = dropped <= MAX_DROPPED;
  return `부하 판정: ${valid ? '유효' : '무효'}. dropped_iterations ${dropped} ${valid ? '<=' : '>'} 한도 ${MAX_DROPPED}`
    + ` (목표 ${TARGET_ITERATIONS}회의 1%).${valid ? '' : ' 이 run 은 버리고 초기화 뒤 다시 돌린다.'}`;
}

export function handleSummary(data) {
  const a = (data.metrics.peekcart_order_accepted || { values: { count: 0 } }).values.count;
  const iteration = data.metrics.iteration_duration;
  const p95 = iteration ? iteration.values['p(95)'] / 1000 : null;
  const sizing = p95 === null ? '반복 p95 없음'
    : `반복 p95 ${p95.toFixed(3)}s, 산식 USERS ${usersFor(RPS, p95)} (이번 USERS ${USERS})`;
  return {
    stdout: `
${loadVerdict(data.metrics)}
=== D-002c ${MODE} (RPS ${RPS} · DUR ${DUR} · 상품 ${PRODUCT_IDS.length}개) ===
${sizing}
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
