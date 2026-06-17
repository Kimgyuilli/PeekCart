# task-impl-saga1-stock-reservation — 사가 클러스터 strangler 1: 재고 예약/복구 이벤트화 (ADR-0010 F2 · ADR-0012 D3)

> Phase 4 구현 ②/④ 교차. Order/Product/Payment 사가 클러스터의 **첫 strangler 단계**.
> 모놀리스 내부에서 **Order 의 동기 Product 재고 변경(차감+복구)을 전부 제거**하고, Product 가 이벤트(`order.created`/`order.cancelled`/`payment.failed`)로 재고를 **소유**하도록 전환한다. `order.created → (Product)예약 → stock.reservation.result → (Order)보상` 경로를 열어 peel 의 선행조건인 동기 결합을 깬다.
> **이 단계는 ADR-0012 D3 의 최종 2-phase 모델이 아니라, D3 경로를 여는 *임시 호환 단계*다** (아래 §2 ADR 관계).

---

## 1. 목표

`OrderCommandService` 가 주문/취소 트랜잭션 내부에서 `ProductPort` 로 재고를 **동기 변경**(차감 `decreaseStockAndGetUnitPrice`, 복구 `restoreStock`)하는 결합(ADR-0010 F2)을 choreography 로 대체한다. **Order 는 더 이상 재고를 차감/복구하지 않는다 — Product 가 이벤트로 단독 소유.** 모놀리스 안에서 happy/실패/취소-순서/멱등을 통합테스트로 독립 검증한다(PR 단위 롤백 가능).

**성공 기준 (검증 가능):**
- Order 코드 경로에서 재고 차감/복구 호출 0건 — `decreaseStock`/`restoreStock` 가 Order 트랜잭션에서 미호출 (grep + 단위테스트 verify).
- order.created → Product **all-or-nothing 예약**(전 품목 가능해야 차감) → `stock.reservation.result(reserved=true)` → 재고 감소 → 주문 PENDING 유지 (통합 happy).
- 일부 품목 부족(예: 2번째 품목) → **부분 차감 없이** `reserved=false, reason=OUT_OF_STOCK` → Order 가 주문 CANCELLED 보상 (통합 부분실패).
- 예약 결과 도착 *전* 주문 취소 → 재고 증식/지연 차감 없음 (취소 선도착이면 tombstone 으로 차감 skip). 예약 성공 주문 취소/결제실패 → Product 가 release(복구 1회), 미예약 주문엔 over-release 없음 (예약 원장 상태머신 CAS).
- 결과/취소 이벤트 중복·역순·DLQ replay 시 멱등 — 복구는 `RESERVED→RELEASED` 원자 CAS 1건만 (통합).
- 예약 결과 미도착(consumer 다운/DLQ 체류) 시 주문이 PENDING 영구 방치되지 않음 — 수렴 경로 또는 명시적 운영 한계 (P6).

## 2. 배경 / 제약

### 현 동기 결합 (코드 감사 — 확인 대상/근거 파일)

`OrderCommandService.createOrder` (`order/application/OrderCommandService.java:53`):
```java
long unitPrice = productPort.decreaseStockAndGetUnitPrice(cartItem.getProductId(), cartItem.getQuantity());
```
→ 한 호출이 (a) 재고 차감(Product 소유) + (b) 단가 스냅샷을 함께 한다. `ProductPortAdapter` 가 `InventoryLockFacade.decreaseStock` + `product.getPrice()` 로 구현.
취소/복구: `cancelOrder:90`·`cancelExpiredOrder:112`·`OrderEventConsumer.handlePaymentFailed:65` 가 `productPort.restoreStock` 동기 호출.
현 `order.created` 소비자: **Payment**(`PaymentEventConsumer:30-44`, `payment-svc-order-created-group`)가 `Payment(PENDING)` 레코드 생성. 실제 charge 는 `PaymentCommandService.pay` → `transitionToPaymentRequested` 로 별도 개시.

### B1 역의존 스윕 — `ProductPort` 인바운드 처분 (PLAN-BLINDSPOTS B1)

확인 대상/근거 파일: `ProductPort.java`(3 메서드: `verifyProductExists`/`decreaseStockAndGetUnitPrice`/`restoreStock`) 와 그 호출처. (구현 PR 에서 createOrder 경로 `decreaseStock` 미호출은 ArchUnit/컴파일 가드로 보장 — P7.)

| 인바운드 간선 | 메서드 | 처분 (이번 단계) |
|---|---|---|
| `OrderCommandService.createOrder:53` | `decreaseStockAndGetUnitPrice` | **디커플** — 재고 차감 제거(비동기 예약으로). 단가는 임시 `getUnitPrice` 동기 read 로 분리(strangler-2 product_cache 시 제거) |
| `OrderCommandService.cancelOrder:90` / `cancelExpiredOrder:112` | `restoreStock` | **디커플** — 동기 복구 제거. 취소는 `order.cancelled` 발행만 → Product 가 release 소유 (P0#2 race 해소) |
| `OrderEventConsumer.handlePaymentFailed:65` | `restoreStock` | **디커플** — 복구 제거. 주문 cancel 만 수행, Product 가 `payment.failed` 소비해 release |
| `CartCommandService.addItem:32` | `verifyProductExists` | **유지** — 장바구니 검증(read). 캐시 전환은 strangler-2/이후 |
| `ProductPortAdapter` (impl) | — | **축소 유지** — 남은 동기 메서드(`verifyProductExists`/`getUnitPrice`)만 서빙. `restoreStock` 은 release consumer 내부로 이동 |
| `OrderCommandServiceTest`·`CartCommandServiceTest` (mock) | `given(productPort...)` | **갱신** — createOrder 가 `getUnitPrice` 만, `decreaseStock`/`restoreStock` 미호출 verify |
| `PaymentEventConsumer.handleOrderCreated` | (order.created 소비) | **유지** — Payment(PENDING) 레코드는 무해(charge 아님). charge 의 예약 게이트(ADR-0012 P1#3)는 strangler-3. 단 "예약실패 후 pay 가 charge 진행 안 됨" 불변식을 P7 테스트로 닫는다 |

### 제약 / 비대상 (Out-of-Scope)

- **2-phase 예약 모델(available/reserved 컬럼 + 확정 commit)은 비대상.** ADR-0012 가 inventory 모델 구현을 **②** 로 명시(`0012:53-56`). 이번 단계는 **예약 = 비동기 재고 차감**, **release = 비동기 재고 복구**(기존 `decreaseStock`/`restoreStock` 의미를 이벤트로 이동). `reserved=true` 는 "예약 상태"가 아니라 **"이미 차감됨"** 을 뜻한다(strangler-3 에서 의미 변경 시 호환 전략 필요).
- **단가 로컬화(`product.updated` 캐시)는 비대상** — strangler-2. 임시 동기 `getUnitPrice` seam 유지.
- **Payment charge 의 예약 게이트 재배선은 비대상** — strangler-3 (Payment 가 `stock.reservation.result` 소비, ADR-0012 D4). 단 미해결 race 를 P7 테스트로 가시화.
- **module peel 없음** — 모놀리스 내부 strangler. 공유 outbox/DB 재사용(Product 전용 outbox 분리는 ②, `0012:37`).

### ADR 관계 (정합 주장 완화 — P1#3)

- **ADR-0010 F2**(재고 차감 트랜잭션 경계 충돌)를 A3 로 위임 → 본 단계가 경계를 깬다.
- **ADR-0012 D3**(재고 예약 choreography, 옵션 B): 신규 토픽 `stock.reservation.result`(Product→Order/Payment). §50 payload = `orderId, reserved, items[](productId,quantity), reason, decidedAt`, 파티션 키 `orderId`. §65 **부분 품목 실패 = all-or-nothing(전체 롤백 + reserved=false)**. §46 envelope `schemaVersion`(하위호환 필드 추가만).
- **본 단계는 D3 의 최종 모델이 아니라 *임시 호환 단계*다.** 만족하지 못하는 ADR 불변식: (1) 2-phase reserved/available(② 로 이연), (2) payment.completed 후 commit 의미(차감 즉시 모델이라 no-op), (3) Payment 예약 게이트(strangler-3). 이번 단계가 닫는 것은 **F2 동기 결합 제거 + 예약/복구 이벤트화 + all-or-nothing**.
- **새 ADR 불필요** — 경계·토픽·payload·envelope·실패계약 모두 ADR-0010/0012 가 보유(로드맵 §2). 바뀌는 건 실행·임시 의미뿐.

## 3. 작업 항목

- [ ] **P1.** Order 의 동기 재고변경 전면 제거 — (a) `ProductPort`: `decreaseStockAndGetUnitPrice` → 임시 `getUnitPrice(Long): long`(read-only, `// strangler-2 product_cache 로 대체` 주석), `restoreStock` 은 인터페이스에서 제거(release 가 Product 내부로 이동). (b) `createOrder` 는 `getUnitPrice` 로 단가만 읽고 차감 호출 제거. (c) `cancelOrder`/`cancelExpiredOrder` 는 `restoreStock` 호출 제거, `order.cancelled` 발행만. (d) `OrderEventConsumer.handlePaymentFailed` 는 `restoreStock` 제거, 주문 cancel 만. → verify: grep 으로 Order 경로 `decreaseStock`/`restoreStock` 0건, 빌드 그린
- [ ] **P2.** 계약 배선 — `:common` 에 `StockReservationResultPayload(Long orderId, boolean reserved, List<ReservedItemPayload> items, String reason, LocalDateTime decidedAt)` + `ReservedItemPayload(Long productId, int quantity)` record. `KafkaEventEnvelope` 에 `int schemaVersion` 추가하되 **기존 4인자 호환 생성자/정적 팩토리**(`schemaVersion=1`) 제공해 기존 호출부 무수정(ADR-0012 §46). `KafkaConfig` 에 `stock.reservation.result`(파티션 3) + `.dlq`(파티션 1) 선언. → verify: 직렬화 스냅샷 테스트(schemaVersion 유무 양쪽 parse), 빌드 그린
- [ ] **P3.** 예약 원장 (상태머신, P0#1·P1#2·P1#4 수렴) — `stock_reservations` 테이블 + Flyway 마이그레이션(**`:common` db/migration 단일 소유, B5**): `order_id`(UNIQUE), `status`(PENDING/RESERVED/CANCEL_REQUESTED/RELEASED/FAILED), `items`(JSON), `source_event_id`(order.created eventId, UNIQUE — 실패발행/예약 중복 방지), `reserved_at`, `released_at`. 엔티티+repository(`product/domain`/`infrastructure`). 모든 상태 전이는 `UPDATE ... WHERE status=?` 원자 CAS. → verify: 마이그레이션 적용, 전이 단위테스트
- [ ] **P4.** Product 발행자 — `ProductOutboxEventPublisher`(`product/infrastructure/outbox`, `OrderOutboxEventPublisher` 패턴 복제: 공유 `OutboxEventRepository`+`MdcSnapshot` 트레이스 전파 재사용, AGGREGATE_TYPE="PRODUCT", aggregateId=orderId) + `publishStockReservationResult(...)`. → verify: 단위테스트(직렬화·aggregateId=orderId·trace 보존)
- [ ] **P5.** Product 예약 consumer (all-or-nothing + tombstone, P0#1·P1#3) — `StockReservationConsumer` `@KafkaListener(topics="order.created", groupId="product-svc-order-created-group")`, `IdempotencyChecker` 멱등. **흐름**: ① 원장에 `CANCEL_REQUESTED` tombstone 있으면 차감 skip → `reserved=false` 발행/no-op 수렴. ② 없으면 **전 품목 재고 선검사 후** 일괄 차감(부분 커밋 금지 — 부족 시 메인 트랜잭션 차감 0). ③ 성공 시 원장 `RESERVED` upsert + `reserved=true` 발행. ④ 부족 시 차감 트랜잭션은 부분차감 없이 종료하고, **`REQUIRES_NEW`** 에서 원장 `FAILED`(`source_event_id` UNIQUE 로 중복 방지) + Product outbox `reserved=false` 를 함께 저장. **저장 성공 시 listener 정상 return → offset commit**; outbox 저장 실패 시에만 예외 전파(Kafka retry). → verify: 통합 happy · "2번째 품목 부족→차감 0" · "cancel 선도착→차감 0"
- [ ] **P6.** Product release consumer (원자 CAS, P0#1·P1#2) — `@KafkaListener` on `order.cancelled` + `payment.failed`(각 group). consumer 별 `processed_events` 로 메시지 중복 방지하되, **복구 권한은 원장 `RESERVED→RELEASED` 원자 CAS 성공 1건일 때만**. `restoreStock`+상태전이를 같은 트랜잭션. `RESERVED` 전 도착(cancel-before-create)이면 `CANCEL_REQUESTED` tombstone upsert(P5 가 참조). → verify: 통합 취소-순서(예약 전→복구 0+tombstone, 예약 후→복구 1) · order.cancelled+payment.failed 동시/역순→복구 1회
- [ ] **P7.** Order 보상 consumer + 수렴 (P1#4) — `OrderEventConsumer` 에 `@KafkaListener(topics="stock.reservation.result", groupId="order-svc-stock-result-group")`, 멱등: `reserved=false` → `order.cancel()`(PENDING→CANCELLED) + `publishOrderCancelled`; `reserved=true` → 주문에 `reservationConfirmedAt`(또는 원장 STOCK_RESERVED) 기록 + no-op. **수렴**: `OrderTimeoutScheduler` 를 PENDING 전체가 아니라 **`PENDING && reservationConfirmedAt is null && orderedAt < cutoff`** 조건으로만 취소(진행중 정상주문 조기취소 방지) → cancel→order.cancelled→Product release. 단순화하려면 스케줄러 확장 대신 **명시적 운영 한계 + 조기취소 방지 테스트**로 대체 가능. `processed_events` retention ≥ DLQ replay 창(ADR-0012 §80-84) 완료조건. → verify: 통합 수렴(결과 미도착→타임아웃 취소, 예약확정 주문 미취소)
- [ ] **P8.** 테스트 종합 — (a) 단위: `OrderCommandServiceTest`/`CartCommandServiceTest` mock 갱신(`decreaseStock`/`restoreStock` 미호출 verify), 신규 consumer·원장 전이 단위, **ArchUnit/컴파일 가드**(Order 경로 stock 변경 금지). (b) 통합(Testcontainers Kafka+MySQL): happy · 부분실패(all-or-nothing) · **cancel-before-create**(취소 선도착→차감 0) · 예약후취소(복구 1) · 동시/역순 release(복구 1회) · 멱등+DLQ replay(Product/Order 각 1회) · trace 전파 · 조기취소 방지(예약확정 주문 미취소) · **pay-before-result race**(예약 결과 전 pay → charge 진행 안 됨; 본 단계에서 못 닫으면 known-limit 으로 문서화하되 ADR 결제순서 충족 주장 금지) · pay-after-fail 차단 · schemaVersion 하위호환. → verify: `./gradlew test` 그린

## 4. 영향 파일

**신규:**
- `common/.../global/outbox/dto/StockReservationResultPayload.java`, `ReservedItemPayload.java`
- `common/src/main/resources/db/migration/V__create_stock_reservations.sql` (예약 원장, B5 단일 소유)
- `product/domain/...` 예약 원장 엔티티+repository, `product/infrastructure/outbox/ProductOutboxEventPublisher.java`
- `product/infrastructure/kafka/StockReservationConsumer.java`(예약), `StockReleaseConsumer.java`(복구)
- 통합테스트 `StockReservationSagaIntegrationTest` + 단위(원장 전이·consumer·publisher) + ArchUnit 규칙

**수정:**
- `order/application/port/ProductPort.java` — `decreaseStockAndGetUnitPrice`→`getUnitPrice`, `restoreStock` 제거
- `order/application/OrderCommandService.java` — createOrder 단가-only, cancel 경로 restore 제거
- `order/infrastructure/kafka/OrderEventConsumer.java` — handlePaymentFailed restore 제거 + result consumer 추가
- `order/infrastructure/scheduler/OrderTimeoutScheduler.java` — `reservationConfirmedAt is null` 조건 수렴(또는 운영한계 택1)
- `order/domain/model/Order.java` — `reservationConfirmedAt` 필드(택1 시)
- `product/infrastructure/adapter/ProductPortAdapter.java` — `getUnitPrice` 만, `decreaseStockAndGetUnitPrice`/`restoreStock` 제거
- `common/.../global/outbox/dto/KafkaEventEnvelope.java` — `schemaVersion` + 호환 생성자
- `global/config/KafkaConfig.java` — 토픽 2개

## 5. 검증 방법

### 자동
- `./gradlew build test` 그린
- 단위: createOrder 가 `decreaseStock`/`restoreStock` 미호출, `getUnitPrice` 호출 (Mockito verify) + ArchUnit(Order→stock 변경 금지)
- 통합(Testcontainers): happy · 부분실패(2번째 품목 부족→차감 0·주문 CANCELLED) · 취소-순서(예약 전 취소→복구 0, 예약 후→복구 1) · 멱등/DLQ replay(중복 1회) · trace 전파 · pay-after-reservation-fail 차단 · schemaVersion 호환

### 수동
- 부팅 후 `stock.reservation.result`(+dlq) 토픽 생성 확인
- 재고 1개 상품에 동시 2주문 → 1건 reserved=false→CANCELLED, 재고 정합 관측

## 6. 완료 조건

- [ ] P1~P8 전부 그린
- [ ] Order 경로 동기 재고 차감/복구 0건 (grep + ArchUnit)
- [ ] all-or-nothing(부분차감 0) · cancel-before-create(차감 0) · 동시/역순 release(복구 1회) · 조기취소 방지 · 멱등/DLQ · 수렴 통합테스트 통과
- [ ] 예약 원장 상태전이 전부 원자 CAS, `order_id`/`source_event_id` UNIQUE
- [ ] `processed_events` retention ≥ DLQ replay 창 명시(ADR-0012 §80-84)
- [ ] 남은 동기 `ProductPort` 메서드(`getUnitPrice`/`verifyProductExists`)는 의도적 잔존 — strangler-2/3 비대상 명시
- [ ] TASKS.md 사가 클러스터 행에 strangler-1 진행 반영

## 7. 트레이드오프 및 결정 근거

- **예약/복구 = 비동기 즉시 차감/복구 (2-phase 아님)**: ADR-0012 가 reserved/available 모델을 ② 로 미룸. 이번 단계는 기존 의미를 이벤트로 옮기는 최소 변경. `reserved=true`="이미 차감됨" — strangler-3 에서 의미가 "예약됨(미확정)"으로 바뀌므로 호환 전략(이벤트 필드/버전) 필요. (P1#3)
- **Order 의 동기 재고변경 전면 제거 (차감+복구)**: 차감만 비동기로 옮기고 복구를 동기로 남기면 예약 결과 전 취소 시 재고 증식/지연 차감 race(P0#2). 따라서 release 도 Product 가 이벤트로 소유.
- **예약 원장 = orderId 상태머신 테이블 (2차 P0#1·P1#2·P1#4 수렴)**: `processed_events`/소형 레코드만으론 (a) cross-topic 순서(취소 선도착) 와 (b) 서로 다른 eventId 의 double-release 를 못 막는다. Kafka 는 토픽 간 순서 무보장이므로 `order.cancelled` 가 `order.created` 보다 먼저 올 수 있음 → tombstone(`CANCEL_REQUESTED`) 으로 차감 skip. 복구는 `RESERVED→RELEASED` 원자 CAS 1건으로만 권한 부여 → 멱등·역순·중복 모두 1회. `order_id`/`source_event_id` UNIQUE. 신규 테이블 1개 + Flyway 1개 비용 수용(정확성 필수).
- **all-or-nothing 예약 (P0#1·P1#3)**: 부분 차감 커밋 방지 — 선검사 후 일괄 차감, 부족 시 메인 트랜잭션 차감 0. 실패 발행은 `REQUIRES_NEW`(원장 FAILED+outbox) 저장 성공 시 정상 return→offset commit, 저장 실패 시에만 예외 전파(retry). `source_event_id` UNIQUE 로 reserved=false 중복 적재 방지.
- **임시 동기 `getUnitPrice` seam**: 단가는 Product 소유, 결과 이벤트(§50)에 price 없음 → 캐시(strangler-2) 전까지 동기 read 불가피. 제거 시점 주석 명시.
- **Payment 재게이트 미포함 + pay-before-result 잔여 race (2차 P1#5)**: Payment(PENDING) 레코드는 무해(charge 아님). 예약실패→주문 CANCELLED 가 후속 pay 를 상태로 차단(pay-after-fail 테스트). 단 **예약 결과 도착 *전* pay 호출**은 ADR-0012 D3 결제순서(`reserved=true` 후 결제)를 아직 만족 못함 — 본 단계에서 Order 의 예약확정 상태로 pay 를 막으면 닫고, 못 닫으면 known-limit 으로 문서화한다. **pay-after-fail 테스트만으로 ADR 결제순서 계약 충족이라 주장하지 않는다.** 완전한 게이트는 strangler-3(Payment `stock.reservation.result` 소비).
- **수렴 경로 (P1#5)**: 비동기 차감에서 result 미도착 시 재고만 빠지고 주문 PENDING 영구 → 타임아웃 스케줄러 확장 또는 운영 한계 명시 + processed_events retention 조건.

## 8. Phase 4 Exit Criteria coverage / 후속

- 기여: "서비스 간 직접 호출 없이 이벤트로 데이터 조합"의 **재고 경로 전환** + F2 동기 결합 해소.
- 후속(클러스터 잔여): strangler-2(단가 `product.updated` 로컬 캐시 → `getUnitPrice` 제거) · strangler-3(2-phase 확정/해제 commit + Payment charge 예약 게이트) · 그 후 Product→Order+Payment peel(② DB 분리 동반).
