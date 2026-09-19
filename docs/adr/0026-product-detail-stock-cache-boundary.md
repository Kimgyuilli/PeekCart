# ADR-0026: 상품 상세 재고 조회 캐시 경계 — 재고 전용 짧은 TTL 캐시

- **Status**: Accepted
- **Date**: 2026-09-18
- **Deciders**: Kimgyuilli
- **관련 Phase**: Phase 4

## Context

상품 상세 조회(`GET /api/v1/products/{id}`)는 상품 정보를 Redis 캐시에서 읽으면서
**재고만 매 호출 DB 에서 읽는다**. 이것은 사고가 아니라 **명시적 결정**이었다 —
`ProductCacheService` javadoc 이 "재고(stock)는 포함하지 않는다 — 재고는 변경 빈도가 높아
별도 실시간 조회한다" 라고 적고 있고 `ProductInfoDto` 에도 같은 주석이 있다.

**측정이 그 결정에 비용을 붙였다.** D-002a GKE 세션(증적 `d002a-gke-20260916-0030.md`)에서
포화 기준(400 VU · CPU limit 3000m) 캐시 배속이 갈렸다.

| endpoint | 캐시 OFF | 캐시 ON | 배속 |
|---|---|---|---|
| detail `/api/v1/products/{id}` | 378.1 rps | 463.6 rps | **×1.23** |
| list `/api/v1/products` | 380.0 rps | 767.1 rps | **×2.02** |

두 엔드포인트의 차이는 **재고 하나**다. 목록 DTO(`ProductListDto`)에는 stock 필드가 아예 없어
적중 시 DB 를 건드리지 않는다.

증적은 원인을 두 개로 적었으나 — 재고 DB 조회와 `@Transactional(readOnly = true)` 개방 —
**후자는 차이를 설명하지 못한다.** `ProductQueryService` 는 클래스 레벨 선언이라 `getProducts`
에도 똑같이 걸리는데 list 는 그 상태로 ×2.02 를 냈다. 게다가 `open-in-view: false` +
Hibernate 지연 커넥션 획득이라 **쿼리가 0이면 물리 커넥션을 잡지 않는다**. 이 ADR 은 증적의
그 서술을 정정한다.

착수 전 코드 검증(계획서 `task-d026-detail-stock-cache-boundary.md` §2)이 확정한 사실:

1. **재고를 바꾸는 주체는 예약 Saga 뿐이다.** `decreaseStock`(예약) · `restoreStock`(보상·취소·
   lease sweeper) · 상품 생성 시 초기 재고. **관리자 재고 수정 API 가 없고**
   `UpdateProductCommand` 에 stock 이 없다 → 동기 요청-응답 경로에서 read-your-write 문제가
   발생할 지점이 없다.
2. **쓰기 경로에 `@CacheEvict` 를 다는 것은 D-025 와 같은 함정이다.** `InventoryService` ·
   `StockReservationService` 는 전부 REQUIRED 로 consumer 트랜잭션에 **참여**하므로,
   `@CacheEvict`(기본 `beforeInvocation=false`)는 **커밋 전**에 돈다. 그 창에 들어온 조회가
   아직 커밋되지 않은 **구값을 다시 캐싱**하면 stale 이 TTL 까지 고착된다.
3. **`product.updated` 이벤트는 재고 변경을 싣고 있지 않다.** 페이로드에 `availableStock` 이
   있으나(ADR-0012 §D2) 발행 지점은 `ProductCommandService` 3곳뿐이고 예약·복구 경로는
   발행하지 않는다. 소비 측(order-service `ProductPriceCache`)은 그 필드를 **버린다**(소비 0건).

## Decision

**상품 상세의 재고를 전용 캐시(`productStock`)에 짧은 TTL(5초)로 올리고, 쓰기 경로에는
무효화를 배선하지 않는다.**

### D1 — 상세 응답 `stock` 의 계약 지위: **표시용 힌트** (예약 보증 아님)

이 결정의 전제이자 가장 중요한 문장이다. 상세 조회의 `stock` 은 **지금도 예약을 보증하지
않는다** — 응답을 받은 직후 다른 주문이 재고를 가져갈 수 있고, 주문 가능 여부의 진실은
예약 Saga(`stock.reservation.result`)와 `PRD-002`(재고 부족)가 정한다.

따라서 TTL stale 은 **새로운 부정확성 클래스를 만드는 것이 아니라, 이미 존재하던 창을
유계로 넓히는 것**이다. "얼마나 stale 해도 되는가" 에 답할 수 있는 근거가 여기서 나온다.

### D2 — 재고 전용 캐시 + TTL 5초

- 캐시 이름 `productStock`, 키 `productId`, TTL **5초**.
- 상품 정보 캐시(`product`, TTL 30분)와 **분리**한다. 두 데이터는 변경 빈도가 두 자릿수 이상
  다르므로 한 엔트리에 합치면 둘 중 하나가 반드시 틀린 TTL 을 갖는다.
- **stale 상한 = TTL = 5초.** 무효화가 없으므로 이 상한이 곧 최악값이다.

**5초인 근거.** 포화 기준 detail ≈ 463 rps 에서 상품당 재고 조회는 초당 수 회~수십 회다.
TTL 5초면 그것이 **상품당 5초에 1회**로 떨어진다 — TTL 을 더 늘려도 DB 조회 감소폭은
빠르게 수확 체감하는 반면 stale 상한은 선형으로 늘어난다. 반대로 1초 미만으로 줄이면
적중률이 떨어져 이 ADR 의 목적이 사라진다. 5초는 "장바구니에 담기 전 눈으로 보는 숫자"로
허용 가능한 범위의 상단이다.

TTL 은 **동작 규약**이므로 `CacheConfig`(Java Config)가 소유한다 — 프로파일에 두지 않는다
(ADR-0007).

### D3 — 쓰기 경로 무효화를 **하지 않는다**

`decreaseStock` / `restoreStock` / sweeper 어디에도 `@CacheEvict` 를 달지 않는다.
정확성은 **TTL 만으로** 유계이고(D2), evict 은 Context 2 의 커밋-전 함정을 안고 온다.

이것은 "나중에 하자" 가 아니라 **결정**이다. 예약 직후 조회가 구값을 반환하는 것은 결함이
아니라 채택한 동작이며, 계약 테스트가 그것을 고정한다. 누군가 evict 을 조용히 끼워 넣으면
그 테스트가 red 가 되어 이 ADR 을 읽게 된다.

### D4 — C(무효화 배선) 승격 조건

다음 중 하나가 관측되면 승격을 검토한다. 그때는 supersede 가 아니라 **D3 의 부분 무효화**다.

- S7 적중률(`cache_gets_total{name="productStock"}`)이 낮아 TTL 이 제 역할을 못 한다
- 5초 stale 이 사용자 경험 문제로 보고된다

승격 시에도 **TTL 은 유지한다** — evict 은 TTL 의 대체재가 아니라 추가물이다(evict↔재조회
race 가 남으므로 상한은 여전히 TTL 이 준다). 그리고 evict 은 반드시
`TransactionSynchronization.afterCommit` 기준이어야 한다.

### D5 — 기존 결정의 명시적 번복

`ProductCacheService` · `ProductInfoDto` javadoc 의 "재고는 변경 빈도가 높아 별도 실시간
조회한다" 는 **이 ADR 로 대체된다**. 변경 빈도가 높다는 관찰은 옳았으나, 그로부터 "캐시하지
않는다" 가 따라 나오지 않는다 — **짧은 TTL 이 그 사이의 선택지**였고 그것을 검토하지 않았다.
낡은 근거를 코드에 남기면 다음 사람이 되돌리므로 javadoc 을 함께 고친다.

## Alternatives Considered

### Alternative A: 재고를 `product` 캐시(TTL 30분)에 합친다
- **장점**: 구현 비용 0. Redis 왕복도 1회로 유지.
- **단점**: stale 상한이 **30분**. 품절 상품을 30분간 "재고 있음"으로 표시한다.
- **기각 사유**: D1 이 허용하는 것은 "유계인 stale" 이지 "긴 stale" 이 아니다. 30분은
  표시용 힌트로도 거짓이다.

### Alternative B: 쓰기 경로에 무효화를 배선한다 (TTL 없이)
- **장점**: 평균 stale 이 짧다.
- **단점**: 쓰기 3경로 변경 + `afterCommit` 동기화 필요. 그럼에도 evict↔재조회 race 가
  남아 **stale 상한이 유계가 아니다** — 그 창에서 구값이 재캐싱되면 다음 쓰기까지 고착된다.
- **기각 사유**: 비용은 더 크고 보장은 더 약하다. TTL 없이는 상한이 없다(D4 참고).

### Alternative C: `product.updated` 이벤트로 재고를 전파한다
- **장점**: 기존 CQRS 배선 재사용. 페이로드에 `availableStock` 이 이미 있다.
- **단점**: 예약·복구마다 outbox 발행이 필요하다 — **조회 최적화를 위해 Saga 토픽에 쓰기
  부하를 얹는 것**이다. D-002 가 이미 outbox 폴링을 사가 처리율 천장으로 지목했다(20/s).
- **기각 사유**: 개선하려는 축(조회)의 이득을 이미 병목인 축(발행)의 비용으로 산다.

### Alternative D: 상세 응답에서 `stock` 을 제거한다
- **장점**: 문제 자체가 사라진다.
- **단점**: `ProductDetailResponse` 공개 API 계약 파괴.
- **기각 사유**: 성능을 이유로 공개 계약을 깨지 않는다.

## Consequences

### 긍정적 영향
- 상세 조회가 캐시 적중 시 **DB 를 전혀 건드리지 않는다**(왕복 1 → 0). 계약 테스트가
  Hibernate `Statistics` 로 이 수를 고정한다.
- 신규 컴포넌트 0개 — 캐시 이름 1 · TTL 1 · 조회 메서드 1. 쓰기 경로 무변경.
- fail-open(`ResilientCacheErrorHandler`)이 `CachingConfigurer.errorHandler()` 전역 공급이라
  새 캐시에도 자동 적용된다(L-006).

### 부정적 영향 / 트레이드오프
- **상세 조회 재고가 최대 5초 낡을 수 있다.** D1 이 이것을 허용 가능하게 만드는 근거지만,
  허용이지 무해는 아니다.
- **Redis 왕복이 요청당 1회 늘어난다**(`product` + `productStock`). 캐시 미스가 잦은
  구간에서는 Redis 2왕복 + DB 1쿼리로 **현재보다 느릴 수 있다**. 적중률이 높은 정상 구간을
  최적화하는 선택이며, S7 이 그 가정을 감시한다.
- **무응답 Redis 에서 상세 조회의 타임아웃 예산이 2배가 된다 — 구현 중 실측으로 드러난 비용이다.**
  상세가 캐시를 둘 타므로 각각 get 500ms + put 500ms 를 소비해 **4회 = 2000ms**가 된다
  (도입 전 2회 = 1000ms). `ProductCacheFallbackIntegrationTest` V3 의 상한을 1.5s → 2.6s 로
  올렸고, 실측은 2.05s 였다. **이것은 fail-open 이 열리는 동안의 지연이지 장애가 아니다** —
  타임아웃이 유계이므로 Lettuce 기본 60s 로 늘어지는 일은 없다(L-006). 다만 Redis 가 죽지 않고
  매달리는 구간에서 상세 응답이 눈에 띄게 느려진다는 뜻이며, 이 값이 문제가 되면 줄일 레버는
  TTL 이 아니라 `spring.data.redis.timeout`(현재 500ms)이다.
- **이 결정은 2차 병목을 건드리지 않는다.** 캐시 OFF 천장이 detail 378.1 / list 380.0 으로
  거의 같다는 것은 공통 자원(MySQL 내부 추정, D-002 미분리)이 천장을 쥔다는 뜻이다.
  재고 쿼리 1개를 없애도 **그 천장은 내려가지 않는다** — 이 ADR 이 개선하는 것은
  **캐시 ON 쪽 처리량**이다.
- **배속 개선폭(×1.23 → ?)은 이 ADR 시점에 미측정이다.** GKE 과금 세션이 필요하다.

### 후속 결정에 미치는 영향
- D-026 은 **부분 해소**로 기록된다 — 구조 변경 완료, 배속 재측정 미완.
- D4 의 승격 조건이 충족되면 D3 를 부분 무효화하는 후속 ADR 이 온다.
- ADR-0012 §D2 와 **충돌하지 않는다** — 그쪽은 이벤트 페이로드 계약이고 조회 캐시 경계를
  규정하지 않는다. 다만 `availableStock` 이 소비 0건인 필드라는 관찰은 남는다(범위 밖).

## References

- 계획서: `docs/plans/done/task-d026-detail-stock-cache-boundary.md`
- 증적: `docs/progress/evidence/d002a-gke-20260916-0030.md` (§읽는 법 ③ — 본 ADR Context 가 정정)
- 코드: `ProductQueryService` · `ProductCacheService` · `CacheConfig` · `ProductDetailQueryCostIntegrationTest`
- 관련 ADR: ADR-0007(설정 소유), ADR-0009/0015(S7 관측성), ADR-0012 §D2(이벤트 페이로드),
  ADR-0025(참여 트랜잭션에서 커밋 전 부수효과 — D3 의 근거)
- 부채: TASKS.md D-026 · D-002(2차 병목 미분리)
