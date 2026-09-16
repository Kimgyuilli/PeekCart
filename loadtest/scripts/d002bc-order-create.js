// D-002b' — 주문 생성 **신규 기준선** (계획서 docs/plans/task-d002-bc-session.md P7)
//
// 원문 D-002 의 "p95 30.21s" 와 **비교하지 않는다**. 그 값은 주문 생성이 동기로 재고를 차감하던
// 시절의 것이고, 그 전제는 소멸했다(계획서 §2 V3): 현재 createOrder 는
//   cart 조회 → 로컬 가격 캐시에서 단가 스냅샷 → order insert → cart clear → outbox publish
// 이고 재고 차감도 Product 동기 호출도 없다. 이것은 새 기준선이다.
//
// **동기 응답만 재면 착시다** — 일이 사라진 게 아니라 비동기로 옮겨갔을 뿐이다. 사가 완결 지연
// (order.created → stock.reservation.result)은 k6 가 아니라 DB 에서 잰다(README 참고).
// 여기서는 동기 구간과 실패 분해만 낸다.
//
// 실행:
//   k6 run -e GW=http://<gateway-lb>:8080 -e PRODUCT_IDS=1,2,3 -e USERS=200 -e RPS=2000 \
//     --summary-export=loadtest/reports/<date>/d002bc-order-create.json \
//     loadtest/scripts/d002bc-order-create.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { SharedArray } from 'k6/data';
import papaparse from 'https://jslib.k6.io/papaparse/5.1.1/index.js';

const GW = __ENV.GW;
const USERS = parseInt(__ENV.USERS || '200');
const RPS = parseInt(__ENV.RPS || '2000');
const PRODUCT_IDS = (__ENV.PRODUCT_IDS || '1').split(',').map((s) => parseInt(s.trim()));

// 한도 상한을 스크립트가 직접 검사한다 — 넘기면 429 가 섞여 측정이 무효가 된다(계획서 §4 P2).
// cart·orders 는 각각 별도 라우트이고 둘 다 사용자별 40 req/s 다. 반복 1회가 양쪽을 1번씩 쓰므로
// 사용자당 안전 상한은 40 iter/s, 전체는 40 × USERS.
const RPS_CEILING = 40 * USERS;
if (RPS > RPS_CEILING) {
  throw new Error(`RPS=${RPS} > 40×USERS=${RPS_CEILING} — 429 가 섞인다. USERS 를 늘려라.`);
}

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
const cartAdd = new Trend('peekcart_cart_add', true);
const rateLimited = new Counter('peekcart_429');
const ord007 = new Counter('peekcart_ord007_price_cache_miss');
const otherFail = new Counter('peekcart_other_fail');

export const options = {
  scenarios: {
    baseline: {
      executor: 'constant-arrival-rate',
      rate: RPS,
      timeUnit: '1s',
      duration: __ENV.DUR || '3m',
      preAllocatedVUs: USERS,
      maxVUs: USERS,
    },
  },
  thresholds: {
    // 429 는 1건도 허용하지 않는다 — 나오면 그 run 은 폐기다(계획서 P2).
    'peekcart_429': ['count==0'],
    // 캐시 전파 게이트(d002bc-seed-products.sh)가 통과했으면 0 이어야 한다. 0 이 아니면 시드 결함이다.
    'peekcart_ord007_price_cache_miss': ['count==0'],
  },
};

export function setup() {
  // 로그인 라우트는 **IP 키 10 req/s**(user-auth-preauth, burstCapacity 10)다 — loadgen VM 이
  // 단일 IP 이므로 여기가 병목이다. 150ms 간격으로 순차 로그인한다(≈6.7/s, 한도 아래).
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
  return { tokens };
}

export default function (data) {
  const idx = (__VU - 1) % data.tokens.length;
  const headers = {
    'Content-Type': 'application/json',
    'Authorization': `Bearer ${data.tokens[idx]}`,
  };
  const productId = PRODUCT_IDS[__ITER % PRODUCT_IDS.length];

  // createOrder 는 **빈 장바구니면 ORD-004 로 죽고**, 성공하면 장바구니를 비운다
  // (OrderCommandService:48·78). 따라서 반복마다 담기가 선행해야 한다.
  const addRes = http.post(`${GW}/api/v1/cart/items`,
    JSON.stringify({ productId, quantity: 1 }), { headers, tags: { step: 'cart_add' } });
  cartAdd.add(addRes.timings.duration);
  if (addRes.status === 429) { rateLimited.add(1); return; }
  if (addRes.status >= 400) { otherFail.add(1); return; }

  const res = http.post(`${GW}/api/v1/orders`,
    JSON.stringify({ receiverName: 'loadtest', phone: '010-1234-5678', zipcode: '06234', address: '서울시 강남구' }),
    { headers, tags: { step: 'order_create' } });
  orderCreate.add(res.timings.duration);

  if (res.status === 429) rateLimited.add(1);
  else if (res.status === 201) { /* ok */ }
  else if (res.body && res.body.indexOf('ORD-007') >= 0) ord007.add(1);
  else otherFail.add(1);

  check(res, { 'order 201': (r) => r.status === 201 });
}
