// D-002a — product 읽기 캐시 배속 격리 측정 (계획서 docs/plans/done/task-d002a-cache-speedup-session.md)
//
// 캐시 ON/OFF 대조군을 같은 부하로 때려 TPS 비를 낸다. gateway 를 경유하지 않으므로
// RateLimiter(40 req/s)도 인증 오버헤드(+9.9ms)도 섞이지 않는다.
//
// 환경변수:
//   TARGET   product-service 주소 (예: http://10.178.0.30:8080)
//   VUS      동시 사용자 (기본 20)
//   DUR      지속 시간 (기본 60s)
//   IDS      조회할 productId 상한 (기본 100) — 캐시 키 분산
//   EP       detail | list (기본 detail)
//            detail = /api/v1/products/{id}
//            list   = /api/v1/products      — CachedPage 반환, 적중 시 DB 미접촉
//
// [D-026 이후 정정] 위 detail 설명은 원래 "캐시 적중해도 재고를 DB 에서 읽는다
// (ProductQueryService.getProduct → inventoryRepository.findByProductId)" 였다.
// **ADR-0026 이 그것을 뒤집었다** — 재고는 이제 전용 캐시 `productStock`(TTL 5s) 뒤에 있고
// 적중 시 DB 왕복은 0이다(D-026, PR #124). 그 서술이 남아 있으면 이 스크립트로 잰 숫자를
// 거꾸로 읽게 된다.
//
// **키 순회(__ITER % IDS)는 바꾸지 않는다.** 기준선(d002a-gke-20260916-0030.md)과 같아야
// 비교가 성립한다. 다만 400 VU 가 비슷한 시점에 같은 ID 를 밟고 @Cacheable 에 sync=true 가
// 없어 TTL 만료 순간 동일 키 stampede 가 가능하다 — 그래서 "상품당 5초에 1회 DB 조회" 는
// 전제가 아니라 **측정 대상**이다(계획서 §V10).
import http from 'k6/http';
import { check } from 'k6';
import { Trend, Rate } from 'k6/metrics';

const TARGET = __ENV.TARGET;
const IDS = parseInt(__ENV.IDS || '100');
const EP = __ENV.EP || 'detail';

export const options = {
  vus: parseInt(__ENV.VUS || '20'),
  duration: __ENV.DUR || '60s',
  // 배속 비교가 목적이라 임계로 run 을 죽이지 않는다 — 실패율은 결과로 본다.
  thresholds: {},
};

const detailLatency = new Trend('detail_latency', true);
const okRate = new Rate('ok_rate');

export default function () {
  const id = (__ITER % IDS) + 1;
  const url = EP === 'list'
    ? `${TARGET}/api/v1/products?page=0&size=20`
    : `${TARGET}/api/v1/products/${id}`;
  const res = http.get(url, { tags: { endpoint: EP } });
  detailLatency.add(res.timings.duration);
  okRate.add(res.status === 200);
  check(res, { 'status 200': (r) => r.status === 200 });
}
