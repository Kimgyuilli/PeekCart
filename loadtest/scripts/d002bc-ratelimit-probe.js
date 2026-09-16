// D-002b'/c — RateLimiter 배선 확인 프로브 (계획서 §4 P2)
//
// **이 스크립트는 429 가 나와야 성공이다.** 측정 전에 반드시 먼저 돌린다.
//
// 왜: b'/c 는 사용자 수로 부하를 올리고(사용자별 40 req/s, 계획서 §2 V8) RateLimiter 설정을
// 건드리지 않는다. 그런데 한도 배선이 죽어 있으면 "429 가 안 나왔다" 는 사실이 **한도 아래에서
// 측정했다는 증거가 되지 못한다** — 그냥 한도가 없는 것이다. 세션 2 는 반대 방향으로 당했다
// (298 req/s 를 밀어 60% 실패, 전부 429). 양쪽 다 배선을 먼저 확인했으면 없었을 일이다.
//
// 단일 사용자로 /api/v1/orders 를 한도(40/s) 위로 때려 429 가 관측되는지 본다.
//
// 실행:
//   k6 run -e GW=http://<gateway-lb>:8080 loadtest/scripts/d002bc-ratelimit-probe.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const GW = __ENV.GW;
const EMAIL = __ENV.EMAIL || 'loaduser0001@peekcart.test';
const PASSWORD = __ENV.PASSWORD || 'LoadTest123!';

const rateLimited = new Counter('peekcart_429');

export const options = {
  vus: 20,
  duration: '15s',
  // 429 를 기대하므로 실패율 임계를 두지 않는다.
  thresholds: {
    // 한도 배선이 살아 있으면 429 가 반드시 나온다. 0 이면 이 프로브가 실패한 것이다.
    'peekcart_429': ['count>0'],
  },
};

export function setup() {
  const res = http.post(`${GW}/api/v1/auth/login`, JSON.stringify({ email: EMAIL, password: PASSWORD }),
    { headers: { 'Content-Type': 'application/json' } });
  if (res.status !== 200) throw new Error(`로그인 실패 ${res.status}: ${res.body}`);
  return { token: res.json('data.accessToken') };
}

export default function (data) {
  // 장바구니가 비어 있어도 상관없다 — 한도는 **라우트 진입 시점**에 걸리므로 400/404 든 429 든
  // 게이트웨이가 무엇을 돌려주는지가 관심사다.
  const res = http.post(`${GW}/api/v1/orders`,
    JSON.stringify({ receiverName: 'probe', phone: '010-0000-0000', zipcode: '00000', address: 'probe' }),
    { headers: { 'Content-Type': 'application/json', 'Authorization': `Bearer ${data.token}` } });
  if (res.status === 429) rateLimited.add(1);
  check(res, { 'gateway 응답함': (r) => r.status !== 0 });
}

export function handleSummary(data) {
  const n = (data.metrics.peekcart_429 && data.metrics.peekcart_429.values.count) || 0;
  const verdict = n > 0
    ? `PASS — 429 ${n}건. 한도 배선 살아 있음. 측정 진행 가능.`
    : `FAIL — 429 0건. 한도가 걸리지 않는다. 이 상태의 "429 없음" 은 한도 아래라는 증거가 아니다.`;
  return { stdout: `\n=== RateLimiter 프로브 ===\n${verdict}\n\n` };
}
