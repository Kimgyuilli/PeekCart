# task-impl-saga2-unit-price-cache — 사가 클러스터 strangler 2: 단가 로컬 캐시 (ADR-0012 ⑤ CQRS · L-006)

> Phase 4 구현 ⑤(CQRS 로컬 캐시) 교차. Order/Product/Payment 사가 클러스터의 **두 번째 strangler 단계**.
> strangler-1 이 임시로 남긴 **동기 `ProductPort.getUnitPrice` seam 을 제거**한다. Product 가 `product.updated` 이벤트로 단가를 발행하고, Order 가 이를 구독해 **로컬 가격 캐시(read model)** 로 적재한 뒤 주문 생성 시 캐시에서 단가를 읽는다.
> 범위 결정(2026-06-16): **단가만**. `verifyProductExists`(장바구니 검증 read)는 동기 `ProductPort` 로 의도적 잔존 — peel(Product→Order) 시 또는 strangler-3 에서 처리.

---

## 1. 목표

`OrderCommandService.createOrder` 가 주문 트랜잭션 내부에서 `ProductPort.getUnitPrice` 로 Product 단가를 **동기 read** 하는 마지막 결합을 choreography CQRS 로 대체한다. **Order 는 더 이상 Product 를 동기로 호출해 단가를 읽지 않고, 로컬 가격 캐시에서 스냅샷을 취한다.** 캐시는 `product.updated` 이벤트로 eventually-consistent 하게 갱신된다.

> **"단가만" 의 의미 (GP-2 #1 반영)**: `product.updated` **토픽 계약은 ADR-0012:48 의 필수 7필드 전체**(`productId, name, price, availableStock, status, categoryId, updatedAt`)로 발행한다 — payload 를 단가로 축소하면 ADR-0012 D2 계약 위반이기 때문(새 ADR 회피의 전제). 범위 "단가만" 은 **Order 캐시가 그중 `price` 만 소비/저장**한다는 뜻이다(나머지 필드는 무시, 후속 캐시 확장 시 재사용).

**성공 기준 (검증 가능):**
- Order 코드 경로에서 `getUnitPrice` 호출 0건 — `ProductPort` 인터페이스에서 `getUnitPrice` 제거(컴파일 가드), `createOrder` 는 로컬 캐시 repository 로 단가 read (grep + 단위테스트).
- Product 가격 등록/수정 → `product.updated` 발행 → Order 로컬 캐시 upsert → 이후 `createOrder` 가 캐시 가격으로 `OrderItem` 단가 스냅샷 생성 (통합 happy).
- 동일 상품 다중 가격수정의 순서 — 파티션 키=`productId` 로 in-order 보장 + **Product `@Version` 기반 monotonic `version` 비교** stale-skip(역순/DLQ replay 시 과거 version 으로 덮어쓰지 않음) (통합).
- 이벤트 중복·replay 시 멱등 — `processed_events` 로 1회 적용 (통합).
- 캐시 미스(아직 `product.updated` 미수신 상품) 처리 정책 명시 — seed + create-시 발행으로 정상경로 미스 제거, 잔여 create→order 경합 창은 문서화된 eventual-consistency 한계로 명시적 에러 (P4·§7).

## 2. 배경 / 제약

### 현 동기 결합 (코드 감사 — 확인 대상/근거 파일)

`OrderCommandService.createOrder` (`order/application/OrderCommandService.java:55`):
```java
long unitPrice = productPort.getUnitPrice(cartItem.getProductId());
```
strangler-1 이 재고 차감을 비동기 예약으로 옮기면서 `decreaseStockAndGetUnitPrice` → read-only `getUnitPrice` 로 축소해 남긴 임시 seam. `ProductPortAdapter.getUnitPrice` (`product/infrastructure/adapter/ProductPortAdapter.java:31`) 가 `productRepository.findById().getPrice()` 로 구현 — Order 가 Product 도메인 read 모델에 동기 결합.

Product 측 변경 경로: `ProductCommandService.create`(신규 등록 시 `Product.create(...price...)`) · `ProductCommandService.update`(`product.update(...command.price()...)`). 현재 어떤 이벤트도 발행하지 않음 — `@CacheEvict`(Redis 읽기 캐시)만 수행.

`product.updated` 토픽/payload/발행자: **전부 미존재** (코드 grep: `ProductPort.java` 주석에만 언급). 이번 단계가 신규 도입.

Product 엔티티는 `BaseTimeEntity`(createdAt only) 상속 — `updatedAt`/`@Version` 없음. 동일 상품 다중 갱신의 순서를 위해 **Product 에 `@Version version` 을 추가**한다(GP-2 라운드2 #1). `OutboxEvent.eventId` 는 `UUID.randomUUID()`(v4, 사전순=인과순서 무관)라 tie-breaker 로 부적합 — 랜덤 UUID 비교는 동일 timestamp 에서 과거 이벤트가 이길 수 있다. 따라서 순서 키는 **per-product monotonic `version`**(엔티티 갱신마다 증가, 충돌 없음). 파티션 키=`productId`(per-partition in-order) + payload `version` 비교 stale-skip 으로 해결.

`ProductStatus` 실제 값은 `{ON_SALE, SOLD_OUT, DISCONTINUED}` 인데 ADR-0012:48 계약은 `{ACTIVE, INACTIVE, SOLD_OUT}` 이다(GP-2 라운드2 #2) → 발행 시 **매핑** 필요: `ON_SALE→ACTIVE`, `DISCONTINUED→INACTIVE`, `SOLD_OUT→SOLD_OUT`. status 변경 경로는 `create`/`update` 외 `ProductCommandService.delete`(→`product.discontinue()`)도 존재(GP-2 라운드2 #3).

### B1 역의존 스윕 — `getUnitPrice` 인바운드 처분 (PLAN-BLINDSPOTS B1)

확인 대상/근거: `grep -rn getUnitPrice src/main src/test`. **주의**: 히트 중 `item.getUnitPrice()`(`OrderItemDto`, `OrderOutboxEventPublisher`, `OrderItemTest`)는 `OrderItem` 도메인 getter 로 **본 작업과 무관 — 유지**. `ProductPort.getUnitPrice` 인바운드만 처분 대상:

| 인바운드 간선 | 처분 (이번 단계) |
|---|---|
| `OrderCommandService.createOrder:55` `productPort.getUnitPrice` | **디커플** — 로컬 캐시 repository read 로 대체 |
| `ProductPortAdapter.getUnitPrice` (impl) | **제거** — 인터페이스에서 사라지므로 impl 도 제거 |
| `OrderCommandServiceTest:46,58` `given/then(productPort).getUnitPrice` (mock) | **갱신** — 캐시 repository stub 으로 교체, `getUnitPrice` mock 제거 |
| `ProductPort.verifyProductExists` + `CartCommandService.addItem` | **유지** — 범위 외(단가만). 동기 `ProductPort` 잔존 명시 |

`getUnitPrice` 인바운드는 위 3건이 전부(테스트 포함) — peel 아닌 단일 메서드 제거라 스윕 표면이 좁다.

### 제약 / 비대상 (Out-of-Scope)

- **`verifyProductExists` 캐시 전환 비대상** — 범위 결정으로 단가만. `ProductPort`/`ProductPortAdapter` 는 `verifyProductExists` 1개 메서드로 축소 잔존(완전 제거는 Product peel 또는 strangler-3).
- **2-phase 예약/재고 모델 비대상** — strangler-3(②). 이번 단계는 가격 read model 만.
- **module peel 없음** — 모놀리스 내부 strangler. 공유 outbox/DB/`processed_events` 재사용. `product_price_cache` 는 `:common` Flyway 단일 소유(B5), Order 소유 read model.
- **Product Redis 읽기 캐시(`@CacheEvict` "product"/"products")는 무관** — Product 자체 조회 캐시. 본 작업의 Order-로컬 가격 캐시와 별개, 건드리지 않음.

### ADR 관계

- **ADR-0012 ⑤ CQRS 로컬 캐시**(Product 변경 이벤트 구독, Order 내 캐시) · L-006 을 구현. 신규 토픽 `product.updated`(Product→Order, ADR-0010 F3·ADR-0012:76)는 이벤트 토폴로지의 일부.
- **payload 계약 = ADR-0012:48 확정 필드 준수** — `productId, name, price, availableStock, status, categoryId, updatedAt` 전체 발행(축소 금지). 파티션 키=`productId`(ADR-0012:47), consumer group `order-svc-product-updated-group`(ADR-0012:76). Order 캐시는 `price` 만 소비.
- **새 ADR 불필요** — 경계·토픽·payload·파티션키·CQRS 결정 모두 ADR-0010/0012 보유, 로드맵 §2 가 클러스터 교차를 명시. envelope `schemaVersion`(ADR-0012:46) 호환 계약 그대로 사용. **단, payload 를 ADR §48 전체로 발행하는 것이 ADR 무위반의 전제**(GP-2 #1).

## 3. 작업 항목

- [ ] **P1.** `product.updated` 계약 + 발행 — (a) `:common` 에 `ProductUpdatedPayload(Long productId, String name, long price, int availableStock, String status, Long categoryId, LocalDateTime updatedAt, long version)` record — **ADR-0012:48 필수 7필드 + `version`(additive 순서 키, ADR §46 하위호환 필드 추가 허용)**. `status` 는 **ADR 값으로 매핑**(`ON_SALE→ACTIVE`/`DISCONTINUED→INACTIVE`/`SOLD_OUT→SOLD_OUT`, GP-2 라운드2 #2), `availableStock` 은 Inventory 조회, `version` 은 Product `@Version`, `updatedAt` 은 발행 시각. (b) **Product 에 `@Version private Long version` 추가** + Flyway `ALTER TABLE products ADD COLUMN version`(기존 행 0 backfill, P2 의 V7 에 동봉). (c) `ProductOutboxEventPublisher.publishProductUpdated(...)` 추가 — **AGGREGATE_TYPE="PRODUCT", aggregateId=productId(파티션 키, ADR-0012:47)**, `KafkaEventEnvelope`(호환 생성자, schemaVersion=1) 직렬화, `MdcSnapshot` trace 전파. (d) `ProductCommandService.create`/`update`/**`delete`(discontinue, status 변경, GP-2 라운드2 #3)** 가 트랜잭션 내 outbox 발행. **⚠️ flush 경계(GP-2 라운드3 #1)**: `@Version` 은 flush 시점에 증가하므로 payload `version` 은 반드시 **`saveAndFlush`(또는 `EntityManager.flush()`) 이후 `product.getVersion()`** 을 읽어 만든다. flush 전 값을 쓰면 기존 row(seed version=0)의 첫 update 가 `version=0` 으로 발행돼 `0 < 0`=false 로 캐시 갱신이 누락된다. `OptimisticLockException` 시 트랜잭션 롤백으로 outbox row 도 남지 않음(기대 동작). (e) `KafkaConfig` 에 `product.updated`(파티션 3) + `.dlq`(파티션 1) 선언. → verify: 직렬화 스냅샷 테스트(7필드+version·status 매핑 문자열 고정·schemaVersion 양쪽 parse), 발행 단위테스트(aggregateId=productId·trace·discontinue 발행·**flush 후 version**), 빌드 그린
- [ ] **P2.** Order 로컬 가격 캐시 read model — (a) `:common` Flyway `V7__product_price_cache_and_product_version.sql`(B5 단일 소유): ① `product_price_cache`(`product_id` PK, `unit_price`, `source_version` BIGINT(마지막 적용 `version`, stale-skip 기준), `updated_at`) ② `ALTER TABLE products ADD COLUMN version`(P1 `@Version`, 기존 행 0 backfill). **+ seed**: 현 `products` 가격/version 을 INSERT(`INSERT ... SELECT id, price, version, ...`) — seed `source_version`=실제 product.version 이라 후속 실제 이벤트(version 증가)가 자연히 승리(별도 sentinel 불요, GP-2 라운드2 #4). `-- strangler-only: peel(DB 분리) 시 product.updated replay 로 대체` 주석. (b) 엔티티 `order/domain/model/ProductPriceCache.java` + repository 인터페이스 `order/domain/repository/ProductPriceCacheRepository.java`(`findUnitPrice(Long): Optional<Long>`, `upsertIfNewer(...)` — `source_version < incoming.version` 비교) + `order/infrastructure` JPA impl. → verify: 마이그레이션 적용 + seed 행수 = products 행수, repository 통합테스트(upsert/조회/낮은 version stale-skip)
- [ ] **P3.** `product.updated` consumer (멱등 + stale-skip) — `order/infrastructure/kafka/ProductPriceCacheConsumer.java`, `@KafkaListener(topics="product.updated", groupId="order-svc-product-updated-group")`, `IdempotencyChecker.executeIfNew(eventId, group, ...)` 멱등. upsert 는 `source_version < incoming.version` 일 때만 적용(역순/DLQ replay 시 과거 version 덮어쓰기 방지 — 파티션키=productId 로 정상경로 in-order, version 비교는 replay 방어, GP-2 라운드2 #1). `KafkaMessageParser` 로 envelope/payload 파싱, `price`+`version` 만 추출(나머지 필드 무시). **`processed_events` retention ≥ `product.updated` topic retention·DLQ replay 창**(ADR-0012 D5/§80-84) 충돌 없음을 확인·문서화(GP-2 #4). → verify: 통합(신규/갱신 가격 반영 · 낮은 version stale-skip · 중복 멱등 1회)
- [ ] **P4.** `getUnitPrice` 동기 seam 제거 + createOrder 캐시 read — (a) `ProductPort` 에서 `getUnitPrice` 제거(`verifyProductExists` 만 잔존), `ProductPortAdapter` 동일 제거. (b) `OrderCommandService.createOrder` 가 `ProductPriceCacheRepository.findUnitPrice(productId)` 로 단가 read. (c) **캐시 미스 정책**: `Optional.empty()` → `OrderException`(신규 또는 기존 적합 에러코드, 예 `ORD-xxx`/`PRD-001` 검토) 으로 명시적 실패. seed + create-시 발행으로 정상경로 미스는 발생 안 함; 잔여 "상품 생성 직후 이벤트 수신 전 주문" 경합 창은 문서화된 한계(§7). → verify: grep Order 경로 `getUnitPrice` 0건(인터페이스 부재=컴파일 가드), 빌드 그린
- [ ] **P5.** 테스트 종합 — (a) 단위: `ProductOutboxEventPublisher` 발행(P1, 7필드+version 직렬화·status 매핑 문자열 고정), consumer stale-skip(낮은 version)/멱등(P3), `OrderCommandServiceTest`/`CartCommandServiceTest` mock 갱신(`getUnitPrice` mock 제거 → 캐시 repository stub, 캐시 미스→에러 verify), **ArchUnit/컴파일 가드**(`ProductPort` 에 `getUnitPrice` 부재). (b) 통합(Testcontainers Kafka+MySQL): product create→`product.updated`→캐시 적재→`createOrder` 가 캐시가격으로 `OrderItem` 스냅샷 · **기존 row(seed version=0) 가격 update→event version=1 이 seed 캐시를 덮음(flush 경계 회귀 테스트, GP-2 라운드3 #1)**·이후 주문 새 가격 · **역순(낮은 version) stale-skip — 최신 version 승리** · discontinue 발행(status=INACTIVE) · 멱등/DLQ replay 1회 · trace 전파 · 캐시 미스→에러 · schemaVersion 하위호환. (c) `processed_events` retention 정합 확인·문서화(GP-2 #4, ADR-0012 D5). → verify: `./gradlew test` 그린

## 4. 영향 파일

**신규:**
- `common/.../global/outbox/dto/ProductUpdatedPayload.java` (ADR-0012:48 7필드 + version)
- `common/src/main/resources/db/migration/V7__product_price_cache_and_product_version.sql` (캐시 테이블: product_id PK·unit_price·source_version·updated_at + `ALTER products ADD version` + seed, B5 단일 소유)
- `order/domain/model/ProductPriceCache.java`, `order/domain/repository/ProductPriceCacheRepository.java`
- `order/infrastructure/ProductPriceCacheJpaRepository.java`, `ProductPriceCacheRepositoryImpl.java`
- `order/infrastructure/kafka/ProductPriceCacheConsumer.java`
- 통합테스트 `ProductPriceCacheSagaIntegrationTest` + 단위(publisher · consumer stale-skip/멱등 · 캐시 repo)

**수정:**
- `product/domain/model/Product.java` — `@Version private Long version` 추가
- `product/infrastructure/outbox/ProductOutboxEventPublisher.java` — `publishProductUpdated` 추가(aggregateId=productId, status 매핑·version 포함)
- `product/application/ProductCommandService.java` — `create`/`update`/`delete`(discontinue) 에서 `product.updated` 발행
- `order/application/port/ProductPort.java` — `getUnitPrice` 제거(`verifyProductExists` 잔존)
- `product/infrastructure/adapter/ProductPortAdapter.java` — `getUnitPrice` 구현 제거
- `order/application/OrderCommandService.java` — createOrder 단가를 로컬 캐시에서 read + 미스 처리
- `order/application/OrderCommandServiceTest.java`, `CartCommandServiceTest.java` — mock 갱신
- `global/config/KafkaConfig.java` — `product.updated` + `.dlq` 토픽 2개

## 5. 검증 방법

### 자동
- `./gradlew build test` 그린
- 단위: createOrder 가 `getUnitPrice` 미호출(인터페이스 부재)·캐시 repository 로 단가 read(Mockito) + 캐시 미스→에러 + ArchUnit(`ProductPort` `getUnitPrice` 부재)
- 통합(Testcontainers): create→updated→캐시→주문 happy · 가격 update 반영 · 역순(낮은 version) stale-skip · status 매핑(discontinue→INACTIVE) · 멱등/DLQ replay 1회 · trace 전파 · 캐시 미스 에러 · schemaVersion 호환
- 캐시 적용 지연(이벤트 발행→upsert lag) 메트릭/로그 노출 확인 (GP-2 #3) · `processed_events` retention ≥ topic retention·DLQ replay 창 정합 확인 (GP-2 #4, ADR-0012 D5)

### 수동
- 부팅 후 `product.updated`(+dlq) 토픽 생성 확인, `product_price_cache` seed 행수 = `products` 행수 확인
- 상품 가격 수정 → 잠시 후 신규 주문 단가가 새 가격으로 스냅샷되는지 관측

## 6. 완료 조건

- [ ] P1~P5 전부 그린
- [ ] Order 경로 동기 `getUnitPrice` 0건 (`ProductPort` 인터페이스 부재 = 컴파일 가드 + ArchUnit)
- [ ] product create/update → `product.updated` 발행(파티션 키=productId) → Order 캐시 upsert
- [ ] stale-skip(역순/replay 낮은 version 미적용·최신 version 승리) · 멱등(중복 1회) 통합테스트 통과
- [ ] `product.updated` payload = ADR-0012:48 7필드 + version, status 는 ADR 값 매핑(ON_SALE→ACTIVE 등) (Order 캐시는 price·version 만 소비)
- [ ] Product `@Version` 추가 + create/update/discontinue 발행 (payload version 은 **flush 후** 읽기 — seed version=0 첫 update→event version=1 덮어쓰기 회귀 테스트 포함)
- [ ] `product_price_cache` seed = 현 products, `processed_events` 멱등 + retention ≥ DLQ replay 창(ADR-0012 D5) 정합 확인
- [ ] 남은 동기 `ProductPort.verifyProductExists` 는 의도적 잔존 — 단가-only 범위 명시
- [ ] TASKS.md 사가 클러스터 행에 strangler-2 진행 반영

## 7. 트레이드오프 및 결정 근거

- **단가만 (verifyProductExists 잔존)**: 범위 결정. `verifyProductExists` 까지 캐시화하면 PR 범위·카트 검증 경로 시맨틱 변경 위험이 커진다. 동기 `ProductPort` 1메서드 잔존은 peel 선행조건을 100% 해소하진 않지만, 가장 무거운 결합(주문 트랜잭션 내 단가 동기 read)을 제거. 완전 제거는 Product peel/strangler-3.
- **순서 보장 = 파티션 키(productId) + Product `@Version` monotonic `version` (GP-2 라운드1 #2 → 라운드2 #1 정정)**: 1차에서 `source_event_id` tie-breaker 를 제안했으나, `OutboxEvent.eventId`=`UUID.randomUUID()`(v4) 라 사전순이 인과순서와 무관 → 동일 timestamp 에서 과거 이벤트가 이길 수 있어 **오히려 위험**. 정정: Product 에 `@Version` 을 추가해 per-product monotonic `version`(갱신마다 증가, 충돌 없음)을 순서 키로 쓴다. 파티션키=productId 로 정상경로 in-order, version 비교는 replay/재정렬 방어. **트레이드오프**: Product 엔티티 변경 + 쓰기 경로 낙관락이 생기지만(범위 증가), 정확한 순서 판정에 필요하고 동시 수정 보호라는 부수 이득도 있어 수용. seed 는 실제 version 을 복사하므로 sentinel 모호성도 제거(라운드2 #4). **단, `@Version` 은 flush 시 증가하므로 payload version 은 flush 후 읽어야 한다**(라운드3 #1) — flush 전 읽으면 seed=0 ↔ event=0 충돌로 첫 갱신 누락.
- **단가 스냅샷 = eventually-consistent 캐시 read (새 허용 조건, GP-2 #3)**: Order 는 `OrderItem` 에 주문시점 단가를 스냅샷. **동기 read 와 본질 동일하지 않다** — 기존 동기 read 는 주문 트랜잭션 시점 Product DB 최신 committed 가격을 읽지만, 새 방식은 `product.updated` 소비 지연 동안 **이전 가격으로 주문 단가가 스냅샷될 수 있음**을 새로 수용한다. 가격 변경은 드물고 지연창은 짧으므로 비즈니스 허용 가능하나, 이는 명시적 새 조건이다. 관측: 캐시 적용 지연(이벤트 발행→캐시 upsert lag)을 메트릭/로그로 노출하고, 지연 급증 시 운영 알림으로 대응한다(검증 항목 반영).
- **캐시 미스 정책 = seed + create-발행 + 명시적 에러**: 공유 DB 모놀리스라 Flyway seed 로 기존 상품 가격을 캐시에 채우고(peel 시 replay 로 대체), 신규 상품은 create 시 `product.updated` 발행으로 채운다. 잔여 경합(상품 생성 트랜잭션 커밋~이벤트 소비 사이에 그 상품 주문)은 드물고, 동기 fallback 을 두면 `getUnitPrice` 가 살아남아 목표(seam 제거)를 위반하므로 두지 않는다 → 명시적 에러 + 문서화된 eventual-consistency 한계. 완전한 무미스는 Product peel 후 read model 부트스트랩에서 정리.
- **seed 를 Flyway 에 둠**: 모놀리스 공유 DB 한정의 부트스트랩 단축. DB 분리(②) 시 cross-DB SELECT 불가하므로 `product.updated` 전량 replay 로 대체 — 마이그레이션에 strangler-only 주석으로 명시.

## 8. Phase 4 Exit Criteria coverage / 후속

- 기여: "서비스 간 직접 호출 없이 이벤트 + 로컬 캐시로 데이터 조합" 의 **단가 경로 전환** — 주문 생성의 마지막 동기 단가 결합 제거.
- 후속(클러스터 잔여): strangler-3(2-phase 확정/해제 commit + Payment charge 예약 게이트, `verifyProductExists` 처리) · 그 후 Product→Order+Payment peel(② DB 분리 동반, seed→replay 전환).
