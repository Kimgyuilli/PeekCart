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
//            detail = /api/v1/products/{id} — 상품 정보와 재고를 **각각의 캐시**에서 조합한다
//                     (ProductQueryService.getProduct → getProductInfo TTL 30분 + getStock TTL 5초).
//                     ADR-0026 이전에는 재고를 매 호출 DB 에서 읽었고 그것이 배속 ×1.23 의 원인이었다.
//                     재고 TTL 이 5초라 적중률은 `키당 초당 요청수 × TTL` 의 함수다 — IDS 를 바꾸면
//                     배속이 따라 움직이므로 비교하려면 IDS 를 고정해야 한다.
//            list   = /api/v1/products      — CachedPage 반환, 적중 시 DB 미접촉
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
