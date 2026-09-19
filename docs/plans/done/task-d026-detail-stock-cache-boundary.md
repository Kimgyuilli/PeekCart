# 계획 — D-026 상품 상세 재고 조회 캐시 경계

> 출처: D-002a GKE 세션 증적 `docs/progress/evidence/d002a-gke-20260916-0030.md` §읽는 법 ③.
> 포화 기준(400 VU · CPU 3000m) 캐시 배속이 **detail ×1.23 ↔ list ×2.02** 로 갈렸고,
> 증적이 원인을 `ProductQueryService.getProduct` 의 재고 DB 조회로 지목했다.
> 순서: **비용 분해(결정적 재현) → ADR-0026 → 구현 → 계약 가드**.
> TASKS.md D-026 이 "캐시 경계 변경은 ADR 선행" 을 명시 — 그 순서를 지킨다.

## 1. 명제 (부정형)

다음 중 **하나라도** 성립하면 미완이다.

- **N1** detail 이 list 보다 캐시 이득이 작은 원인이 **재고 SELECT 1회** 라는 것이
  추정에 머물고, **결정적 단언**(캐시 적중 시 발행 SQL 수)으로 고정되지 않았다.
- **N2** 재고를 캐시에 올린 뒤 노출될 수 있는 **stale 재고의 상한이 유계가 아니거나**,
  그 상한이 테스트로 강제되지 않는다 — 즉 TTL 을 늘려도 테스트가 그린이다.
- **N3** 재고 캐시가 쓰기 경로(예약 saga)에 **무효화 의무를 새로 지우는데**, 그 의무가
  **커밋 시점 기준이 아니다**. (D-025 와 같은 함정 — 참여 트랜잭션에서 메서드 반환 시점
  부수효과는 커밋보다 먼저 돈다. 그 창의 재조회가 구값을 다시 캐싱하면 stale 이 **영구**가 된다.)
- **N4** 상세 응답의 `stock` 이 **예약 보증인지 표시용 힌트인지**가 계약으로 적히지 않았다.
  이 문장이 없으면 "얼마나 stale 해도 되는가" 에 답할 근거가 없다.
- **N5** Redis 장애 시 재고 조회가 **fail-open 으로 DB 로 흐르는지** 검증되지 않았거나,
  `docs/runbooks/redis-cache-fallback.md` 가 새 캐시를 모른다.
- **N6** 위 결정들이 **ADR-0026 으로 기록되지 않았거나**, 구현이 그 결정과 다르다.
- **N7** ADR-0009 S7 관측성 계약(`cache_gets_total`)이 **새 캐시 이름을 포함하지 않는다**
  — 적중률을 못 보면 TTL 값이 맞는지 판정할 수단이 없다.
- **N8** 처리량 이득(×1.23 이 얼마가 되었는가)이 **미측정인데 D-026 을 "해소" 로 표기**한다.

---

## 2. 배경 — 착수 전 코드 검증

**전제는 ADR 이 아니라 현재 코드로 확인했다.** 아래 표의 판정은 전부 파일을 직접 열어 얻었다.

| # | 전제 | 확인 결과 | 영향 |
|---|---|---|---|
| **V1** | `getProduct` 가 상품은 캐시, 재고는 매번 DB 에서 읽는다 | **확증.** `ProductQueryService:43` `productCacheService.getProductInfo`(@Cacheable) → `:45` `inventoryRepository.findByProductId`(무조건 DB) | D-026 원문대로. 단 **행 번호 정정**: TASKS.md 는 `getProduct:44` 라 적었으나 재고 조회는 **`:45`** 이고 `:44` 는 빈 줄이다 |
| **V2** | 이 경계는 **의도된 결정**이었다 | **그렇다.** `ProductCacheService:32` javadoc — "재고(stock)는 포함하지 않는다 — 재고는 변경 빈도가 높아 별도 실시간 조회한다". `ProductInfoDto:7` 에도 같은 주석 | D-026 은 버그 수정이 아니라 **기존 결정의 재검토**다. ADR 이 필요한 진짜 이유이고, ADR-0026 은 이 문장을 **명시적으로 뒤집어야** 한다 |
| **V3** | 증적이 지목한 또 하나의 비용 — `@Transactional(readOnly=true)` 가 캐시 적중과 무관하게 열린다 | **사실이나 원인이 아니다.** 클래스 레벨 선언(`ProductQueryService:18`)이라 **`getProducts` 도 똑같이 열린다**. 그런데 list 는 ×2.02 를 냈다 → 트랜잭션 개방은 두 경로의 **공통 비용**이라 차이를 설명하지 못한다. 게다가 `open-in-view: false`(`application.yml:29`) + Hibernate 지연 커넥션 획득이라 **쿼리가 0이면 물리 커넥션을 잡지 않는다** | **증적 문서의 서술을 정정한다.** 차이를 만드는 것은 재고 SELECT **1회와 그에 딸린 커넥션 획득**이다. N1 이 이것을 단언으로 고정한다 |
| **V4** | list 도 재고를 담는다 | **아니다.** `ProductListDto:10-16` 에 stock 필드가 없다 → 적중 시 **DB 무접촉**. 부하 스크립트 주석도 같은 취지(`loadtest/scripts/d002a-product-read.js:13-15`) | detail/list 비교가 "재고 유무" 단일 축으로 깨끗하게 갈린다 → V3 의 정정을 뒷받침 |
| **V5** | 재고를 바꾸는 경로가 몇 개인가 | **3개 + 초기 생성.** `StockReservationService:87 decreaseStock`(예약) · `:239 restoreStock`(보상/취소) · `StockReservationLeaseSweeper` → `sweepExpiredLeases` → `restoreStock`(별도 스케줄러 스레드) · `ProductCommandService:51`(상품 생성 시 초기 재고) | 무효화 배선을 택하면 **최소 3곳**을 건드려야 한다. TTL-만 방식이면 **0곳** |
| **V6** | 관리자 재고 수정 API 가 있다 | **없다.** `AdminProductController` 는 생성(`:39` stock 포함)·수정·삭제뿐이고 **재고 단독 변경 엔드포인트가 없다**. `UpdateProductCommand` 에 stock 없음 | 재고는 **saga 만** 바꾼다 → 동기 요청-응답 경로에서 "내가 방금 바꿨는데 안 보인다" 류 read-your-write 문제가 **발생하지 않는다**. TTL 방식의 위험이 한 단계 낮다 |
| **V7** | 쓰기 경로에 `@CacheEvict` 를 달면 된다 | **위험하다 — D-025 와 같은 함정.** `InventoryService:20`·`StockReservationService:32` 는 전부 기본 REQUIRED 로 **consumer 트랜잭션에 참여**한다. `@CacheEvict`(기본 `beforeInvocation=false`)는 **메서드 반환 시점**에 돌고 그 시점은 **커밋 전**이다. 그 창에 들어온 조회가 아직 커밋되지 않은 **구값을 다시 캐싱**하면 stale 이 TTL 까지 고착된다 | 무효화 배선을 택하더라도 **`afterCommit` 동기화가 필수**이고, 그래도 evict↔재조회 race 는 남는다 → **짧은 TTL 이 여전히 필요**하다. 즉 evict 은 TTL 의 대체재가 아니라 **추가물**이다 |
| **V8** | `product.updated` 이벤트로 재고를 흘리면 된다 | **이미 흐르고 있으나 쓸 수 없다.** `ProductUpdatedPayload` 에 `availableStock` 이 있고(ADR-0012 §D2 필수 필드) `ProductOutboxEventPublisher:67` 이 채운다. 그러나 **발행 지점이 `ProductCommandService` 3곳(create/update/delete)뿐**이고 **예약·복구 경로는 발행하지 않는다**. 소비 측 `ProductPriceCache`(order-service)는 `unitPrice`/`sourceVersion` 만 적재하고 **`availableStock` 을 버린다**(전 모듈 grep: 소비 0건) | 이벤트 경로는 **재고 변경을 싣고 있지 않다.** 이 축으로 가려면 예약/복구마다 `product.updated` 를 발행해야 하고, 그건 **조회 최적화를 위해 saga 토픽에 쓰기 부하를 얹는 것**이다 → 선택지에서 기각 후보. (부수 관찰: `availableStock` 이 **아무도 안 쓰는 필드**로 계약에 남아 있다 — 범위 밖, §미해결) |
| **V9** | 캐시 인프라는 새로 만들 것이 없다 | **그렇다.** `CacheConfig`(Java Config, ADR-0007 준수)에 캐시별 TTL 배선 지점이 있다(`:95-101`) · fail-open 은 `ResilientCacheErrorHandler` 가 **`CachingConfigurer.errorHandler()` 로 전역 공급**(`:120-123`)이라 새 캐시에도 자동 적용 · `peekcart.cache.enabled` 토글 2빈 구조도 그대로 탄다 | 신규 컴포넌트 0개. **캐시 이름 1개 + TTL 1개 + 조회 메서드 1개**가 구현 전부여야 한다 |
| **V10** | S7 관측성 계약이 캐시 이름을 열거한다 | **테스트가 열거한다.** ADR-0009 S7 은 수단(`enableStatistics`)을 고정하고, `ProductObservabilityMetricsIntegrationTest:81-94` 가 **`name="products"` 만** 단언한다. ADR-0015 가 per-service 로 정정한 뒤에도 이 단언은 그대로 | 새 캐시는 **메트릭이 자동으로 나오지만 단언은 자동으로 생기지 않는다** → N7. 단언 없으면 "적중률을 못 보는 캐시"가 된다 |
| **V11** | 기존 캐시 테스트가 재고 경계를 시험한다 | **아니다.** `ProductCacheIntegrationTest.getProduct_cacheHit:88-97` 은 캐시 **엔트리 존재**와 `id`/`name` 일치만 본다 — **재고가 어디서 왔는지 묻지 않는다**. 재고를 캐시에 올려도 이 테스트는 **그대로 그린**이다 | **false-green 위험이 이미 있다.** N1/N2 의 검증은 이 테스트를 고치는 게 아니라 **SQL 발행 수를 세는 새 단언**이어야 한다 |
| **V12** | 처리량 재측정 하네스가 있다 | **있다.** `loadtest/scripts/d002a-product-read.js` 가 `EP=detail\|list` · `peekcart.cache.enabled` 대조군 · overlay `k8s/overlays/gke-d002a` 까지 갖췄다. 그러나 **실행은 GKE 과금 세션**이 필요하다(쿼터 12 vCPU) | N8 — 로컬에서 닫을 수 있는 것은 **SQL 수·stale 상한**이고, **배속 숫자는 다음 세션 몫**이다. 이 경계를 계획서가 먼저 인정한다 |

### 구조 변경 여부

모듈/경계 이동·peel·rename **아니다**(캐시 이름 1개 추가 + 조회 메서드 1개). `PLAN-BLINDSPOTS.md`
전체 수행 대상이 아니나, V9 의 "신규 컴포넌트 0" 을 확인하기 위해 **B1 역의존 스윕만** 수행했다.

| 대상 | 인바운드 참조 | 처분 |
|---|---|---|
| `ProductQueryService.getProduct` | main: `ProductController:44` **1곳** / test: 6개 파일 14곳 | 시그니처 불변 → 호출자 변경 0 |
| `ProductDetailDto.stock` | `ProductDetailResponse:17,30`(API 응답 필드) | **응답 계약 불변** — 값의 출처만 바뀐다. N4 가 그 의미를 문서로 고정 |
| `InventoryRepository.findByProductId` | `ProductQueryService:45` · `InventoryService:35,48,60` · `ProductCommandService:80,106` | 조회 경로 1곳만 캐시 뒤로 간다. **쓰기 경로는 계속 직접 호출**(캐시 우회가 정답 — 쓰기는 managed 엔티티가 필요하다) |

---

## 3. 선택지와 권고

| | 방식 | 정확성 | 비용 | 판정 |
|---|---|---|---|---|
| **A** | 재고를 `product` 캐시에 합친다 (TTL 30분) | stale ≤ **30분** | 0 | **기각.** 품절 상품을 30분간 재고 있음으로 표시한다 |
| **B** | **재고 전용 캐시 + 짧은 TTL** (예: 5초) | stale ≤ **TTL** (유계) | 캐시 이름 1 · TTL 1 · 메서드 1. **쓰기 경로 무변경**(V5/V6) | **권고** |
| **C** | B + 쓰기 경로 `afterCommit` 무효화 | stale ≤ TTL (동일, 평균만 개선) | **쓰기 3경로**(V5) + 트랜잭션 동기화 + race 잔존(V7) | **유보** — B 의 적중률 실측 후 필요하면 승격 |
| **D** | 이벤트(`product.updated`)로 재고 전파 | eventually | 예약·복구마다 outbox 발행 — **조회 최적화를 위해 saga 에 쓰기를 얹는다**(V8) | **기각** |
| **E** | 상세 응답에서 `stock` 제거 | — | API 계약 파괴 | **기각** |

**B 를 권고하는 근거는 N4 의 답에 있다.** 상세 조회의 `stock` 은 **지금도 예약을 보증하지 않는다**
— 응답을 받은 직후 다른 주문이 재고를 가져갈 수 있고, 주문 가능 여부의 진실은 예약 saga
(`stock.reservation.result` · `PRD-002`)가 정한다. 즉 TTL stale 은 **새로운 부정확성 클래스를
만드는 것이 아니라 이미 존재하던 창을 유계로 넓히는 것**이다. ADR-0026 은 이 문장을 계약으로
적고, 그 위에서 "몇 초까지 넓혀도 되는가" 를 정한다.

C 를 지금 하지 않는 이유는 D-025 와 같다 — **기여가 측정되지 않은 수단을 먼저 배선하지 않는다.**
B 의 적중률(S7)을 보고 stale 노출이 실제로 문제가 될 때 승격하며, 그때는 supersede 가 아니라
ADR-0026 의 부분 무효화로 간다.

> **TTL 값은 계획이 아니라 ADR 이 정한다.** 초안 권고는 5초 — 400 VU 포화(≈460 rps) 기준
> 상품당 DB 조회가 초당 수십회에서 **TTL 당 1회**로 떨어지고, stale 상한 5초는 "장바구니에
> 담기 전 눈으로 보는 숫자" 로 허용 가능하다. 최종 값은 P2 의 근거와 함께 ADR 에 적는다.

---

## 4. 작업 항목

- [x] **P1.** **비용 분해를 결정적 단언으로 고정한다** (수정 **전**, red/green 기준선).
  `product` 캐시 적중 상태에서 `getProduct` 와 `getProducts` 가 발행하는 **SQL 문 수**를
  Hibernate `Statistics`(`getPrepareStatementCount`)로 센다. 기대: **detail=1 · list=0**.
  이 테스트는 현재 코드에서 **통과**해야 하고(현상 고정), P3 이후 **detail=0** 으로 뒤집힌다.
  → N1.
- [x] **P2.** **ADR-0026 작성** — `docs/adr/0026-product-detail-stock-cache-boundary.md`.
  결정 내용: ① 상세 `stock` 의 계약 지위(**표시용 힌트**, 예약 보증 아님 — N4) ② 선택지 B 채택과
  A/C/D/E 기각 근거 ③ **TTL 값과 그 근거** ④ stale 상한 = TTL ⑤ 쓰기 경로 무효화를 **하지 않는다는**
  결정과 C 승격 조건 ⑥ `ProductCacheService:32`·`ProductInfoDto:7` javadoc 이 표명한 **기존 결정을
  뒤집는다**는 명시(V2). Status `Accepted`, 무효화 대상 — ADR-0012 §D2 와 충돌 없음(그쪽은 이벤트
  페이로드 계약이고 조회 캐시 경계를 규정하지 않는다). `docs/adr/README.md` 인덱스 추가. → N6.
- [x] **P3.** **재고 캐시 도입.** `CacheConfig` 에 `PRODUCT_STOCK_CACHE` 상수 + TTL 배선(`:95-101` 패턴),
  `ProductCacheService` 에 `@Cacheable(cacheNames = "productStock", key = "#productId")` 조회 메서드
  추가, `ProductQueryService:45` 를 그 호출로 교체. **쓰기 경로·`InventoryService`·`ProductCommandService`
  는 건드리지 않는다**(V5/V6 근거). TTL 은 Java Config 소유(ADR-0007 — 동작 규약).
- [x] **P4.** **stale 상한을 강제하는 테스트.** 캐시 적중 상태에서 **DB 재고를 직접 UPDATE** 한 뒤
  ① TTL 경과 **전** 조회 = 구값 ② TTL 경과 **후** 조회 = 신값 을 단언한다. TTL 을 테스트 프로파일에서
  짧게(예: 1초) 주입해 결정적으로 만든다. **실패 주입**: TTL 을 무한대로 바꾸면 ②가 red 여야 한다
  (= 이 테스트가 "언젠가 맞으면 통과" 가 아님). → N2.
- [x] **P5.** **쓰기 경로 무변경을 가드로 고정.** 예약(`decreaseStock`)으로 재고가 줄어든 직후,
  캐시가 **아직 구값을 반환한다**는 것을 단언한다. 이것은 결함이 아니라 **채택한 결정**이고
  (ADR-0026 D-stale), 누군가 나중에 evict 을 조용히 끼워 넣으면 이 테스트가 red 가 되어
  **ADR 을 읽게 만든다**. 커밋 전 evict(V7)의 함정도 이 자리에 주석으로 고정한다. → N3.
- [x] **P6.** **Redis 장애 fail-open 검증.** `ProductCacheFallbackIntegrationTest` 패턴을 따라,
  Redis 차단 상태에서 `getProduct` 가 **재고를 DB 에서 읽어 200 을 반환**하는지 단언한다
  (`ResilientCacheErrorHandler` 전역 공급 — V9 가 배선은 확인했으나 **동작은 미검증**). → N5.
- [x] **P7.** **S7 관측성 계약 확장.** `ProductObservabilityMetricsIntegrationTest` 에
  `cache_gets_total{name="productStock", result="hit|miss"}` 단언 추가(기존 `assertCacheGetLine`
  재사용, 중복 시계열 부재 포함). ADR-0009 S7 행 / ADR-0015 per-service 표에 캐시 이름 반영 여부를
  확인하고 필요 시 정정. → N7.
- [x] **P8.** **runbook 갱신.** `docs/runbooks/redis-cache-fallback.md` §2.1 감시 PromQL 과 §4.2
  수동 삭제(SCAN 패턴 `cache:productStock:*`)에 새 캐시를 반영한다. §4.1 "TTL 만료 대기" 가
  재고 캐시에서는 **≤TTL** 로 훨씬 짧다는 점을 명시.
- [x] **P9.** **문서 정정 3건.** ① `ProductCacheService:32`·`ProductInfoDto:7` javadoc — 뒤집힌 결정을
  반영(낡은 근거를 남기면 다음 사람이 되돌린다) ② `docs/progress/evidence/d002a-gke-20260916-0030.md`
  §읽는 법 ③ 의 `@Transactional(readOnly=true)` 서술 — V3 정정을 **추기**(증적은 immutable 이므로
  덮어쓰지 않고 정정 블록 추가) ③ `docs/04-design-deep-dive.md`·`05-data-design.md` 에 캐시 경계
  서술이 있으면 `(see ADR-0026)` 참조 추가.
- [x] **P10.** **TASKS.md / PHASE4.md 갱신.** D-026 을 **"부분 해소 — 구조 변경 완료, 배속 재측정 미완"**
  으로 적는다. **"✅ 완료" 로 적지 않는다**(N8) — 다음 GKE 세션의 측정 항목으로 등록한다.

---

## 5. 검증 방법

**"존재한다 / 배선됐다" 는 검증이 아니다.** 각 항목은 실패를 주입한 뒤 상태로 확인한다.

| 항목 | 실패 주입 | 관측 지점 | 기대 |
|---|---|---|---|
| P1 | — (현상 고정) | Hibernate `Statistics.getPrepareStatementCount` | 수정 전 detail=**1** / list=**0** → 수정 후 detail=**0** |
| P3 | 캐시 어노테이션 제거 | 위와 동일 | detail=1 로 되돌아가 **red** |
| P4 | TTL 을 무한대로 변경 | TTL 경과 후 조회값 | 구값 반환 → **red** (= 시간 의존이 실재함을 증명) |
| P4 | DB 직접 UPDATE (캐시 우회) | TTL 경과 전 조회값 | **구값** — stale 이 실제로 존재함을 양성 확인 |
| P5 | 쓰기 경로에 `@CacheEvict` 추가 | 예약 직후 조회값 | 신값 반환 → **red** (ADR 미독 배선을 차단) |
| P6 | Redis 연결 차단 | HTTP 200 + 재고값 | DB 값으로 200. 예외 전파 시 **red** |
| P7 | 캐시 이름 오타 / 수동 `CacheMetricsRegistrar` 추가 | `/actuator/prometheus` 본문 | 시계열 0개 또는 2개 → **red** |

**false-green 차단 근거**: V11 이 확인했듯 기존 `getProduct_cacheHit` 은 재고 출처를 묻지 않아
이 변경에 **무감각**하다. 그래서 검증의 축을 "캐시 엔트리 존재" 가 아니라 **SQL 발행 수**(P1/P3)와
**시간 경과에 따른 값 전이**(P4)로 잡았다. 둘 다 어노테이션을 지우면 즉시 red 가 된다.

---

## 6. 완료 조건

1. §1 의 N1~N8 이 전부 **거짓**이다.
2. ADR-0026 이 `Accepted` 로 존재하고 README 인덱스에 있으며, 구현이 그 결정과 일치한다.
3. product-service 스위트 그린 + **전 8모듈 스위트 그린**(로컬 자원 부족 시 CI 확인 후 머지 —
   구현 ⑥ 선례).
4. ~~Codex 계획 리뷰 수렴~~ → **미호출 확정 (2026-09-18, 사용자 지시).** 사유는 아래 §8.
   **"P0/P1 = 0" 주장을 하지 않는다.**
5. TASKS.md 의 D-026 이 **"부분 해소"** 로 적혀 있고, 배속 재측정이 다음 GKE 세션 항목으로 등록됐다.

---

## 7. 미해결 (범위 밖 — 처분 명시)

- **배속 재측정(×1.23 → ?)** — GKE 과금 세션 필요(V12). **D-026 을 "완료" 로 닫지 않는 이유**이고,
  같은 세션에서 D-002 P5(MySQL CPU 상한 미분리)와 묶는 것이 효율적이다.
- **2차 병목 "MySQL 내부"** — 캐시 OFF 천장이 detail 378.1 / list 380.0 으로 거의 같다는 것은
  공통 자원이 천장을 쥔다는 뜻이다(증적 §2차 병목). 재고 쿼리 1개를 없애도 **그 천장은 안 내려간다**
  — D-026 이 개선하는 것은 **캐시 ON 쪽 처리량**이다. 이 구분을 ADR Consequences 에 적는다.
- **`ProductUpdatedPayload.availableStock` 이 소비 0건**(V8) — ADR-0012 §D2 필수 필드인데
  order-service 는 버린다. 계약 정리 대상이나 **이번 변경과 무관**하다. TASKS.md 에 관찰로만 남긴다.
- **C(쓰기 경로 무효화) 승격** — B 의 S7 적중률과 stale 민원을 보고 판단. 조건을 ADR-0026 에 적는다.
- **`products` 목록 캐시** — 재고를 담지 않으므로(V4) 이번 변경과 무관.

---

## 8. 리뷰 처분 — Codex 미호출

**계획 리뷰를 호출하지 않았다.** 두 가지가 겹쳤다.

1. **실행 불가** — `@openai/codex@0.155.0` 이 전역 설치돼 있으나 플랫폼 바이너리
   (`@openai/codex-darwin-arm64`)가 빠져 `node .../bin/codex.js` 가 즉시 죽는다.
   `codex` 는 PATH 에도 없다.
2. **사용자 지시** — 재설치 대신 **미호출로 진행**을 선택(2026-09-18). D-025 와 같은 처분이다.

따라서 이 계획서에는 **외부 리뷰가 붙지 않았다.** §2 의 V1~V12 는 전부 내가 파일을 직접 열어
확인한 것이고, 그 이상의 교차검증은 없다. `/work` 단계의 diff 리뷰도 같은 제약을 받는다.

**선택지 B 채택은 사용자 확정**(2026-09-18) — ADR-0026 은 이 결정을 기록하는 문서이지
결정을 여는 문서가 아니다. 남은 미정은 **TTL 값 하나**이며 P2 에서 근거와 함께 고정한다.
