# task-impl-order-payment-decouple — Order↔Payment 동기 결합 제거 (peel 선행 strangler)

> Product 가 ① 단독 peel 불가였던 것과 동일하게, **Payment 가 Order 의 동기 인-프로세스 빈(`OrderPort`)에 묶여** Order/Payment 를 독립 모듈로 peel 할 수 없다. 본 PR 은 모놀리스 안에서 그 동기 seam 을 이벤트/로컬 상태로 strangle 해 **Order↔Payment src/main 상호 참조 = 0** 을 만든다(가드 강제). 실제 두-모듈 peel + root app 소멸은 **후속 PR-B**(사용자 게이트 2026-06-18: "strangler 먼저, peel 후").
> 선행 ADR: ADR-0010(5개 서비스 경계·이벤트 토폴로지), ADR-0012(DB/이벤트/Saga 계약 — §D4 토픽 매트릭스·§D3 reserve→pay 게이트 SSOT), ADR-0014(전환기 인증). **새 ADR 불필요** — GP-1 게이트(사용자 2026-06-18): 신규 토픽 `payment.requested`(7번째) + ORD-008 게이트 재구성은 **ADR-0012 §D4/§D3 의 refine 으로 본 PR 안에서 처리**(선례: ADR-0012 §78 이 ADR-0010 토폴로지를 새 ADR 없이 refine). 본 PR 의 P7 작업항목.
> strangler 선례: 사가 클러스터 #56~#61(Order↔Product 동기 결합 0 → Product peel #62).

## 1. 목표

Payment 가 Order 도메인 빈에 동기로 의존하는 유일 seam 인 `OrderPort`(인터페이스: payment, 구현체 `OrderPortAdapter`: order)를 제거한다. 두 호출을 각각 (a) payment-로컬 데이터, (b) 이벤트로 대체하고, 제거 후 `OrderPort`/`OrderPortAdapter` 를 삭제한다. ORD-008 결제 게이트(취소된 주문에 결제 금지)와 `paymentRequestedAt` 타임아웃 앵커(strangler-3 의미)는 **동기 결합 없이 보존**한다.

**성공 기준**:
- `grep -rn "com\.peekcart\.payment" src/main/java/com/peekcart/order` **및** `grep -rn "com\.peekcart\.order" src/main/java/com/peekcart/payment` → **둘 다 0건**.
- 신규 가드 `assertNoOrderPaymentSourceCoupling` 통과(src/main order↔payment 상호 참조 0, 회귀 방지).
- `./gradlew build test` 전체 BUILD SUCCESSFUL.
- ORD-008 게이트 회귀 테스트 그린: 취소된 주문 / 예약 미확정(`reserved` 미수신) 주문에 대한 `confirmPayment` 가 거부됨(payment-로컬 게이트), reserved=true·미취소 정상 주문은 통과.
- reserve→pay 순서(ADR-0012 §D3) 보존: `reserved=true` 로컬 수신 전 confirm 거부. 비동기 잔여 창(게이트 통과 후 Toss 전 취소 커밋)은 ADR-0012 §D3 ④ 보상(환불+운영 알림)으로 수렴.

## 2. 배경 / 제약

### 범위 결정 (사용자 게이트 2026-06-18)
- **이번 PR = 동기 seam 제거(strangler)만.** 두-모듈 peel(order-service·payment-service 생성)·root app 소멸·global.{outbox,idempotency,config} 재분배·가드 재작성·root 테스트 디커플은 **후속 PR-B**. 본 PR 은 코드를 모듈 간 이동시키지 않는다(모놀리스 내부 배선만 변경).
- **GP-1(2026-06-18)**: ADR-0012 §D4 매트릭스(6토픽 SSOT)에 7번째 토픽 `payment.requested` 추가 + §D3 게이트 재구성 → **ADR-0012 refine 을 본 PR P7 로 흡수**(새 ADR 미작성).

### 현재 코드 (grep 검증 완료 — 의존 *방향* 확정)
- **seam = Payment → Order 동기 호출 3곳, 2메서드** (`OrderPort` 인터페이스는 payment 소유, 구현 `OrderPortAdapter` 는 order 소유 → payment 가 order 빈에 런타임 의존):
  - `PaymentCommandService:40` `orderPort.verifyOrderOwner(userId, orderId)` (읽기: 주문 소유자 검증)
  - `PaymentQueryService:28` `orderPort.verifyOrderOwner(userId, orderId)` (읽기)
  - `PaymentCommandService:46` `orderPort.transitionToPaymentRequested(orderId)` (쓰기: order → PAYMENT_REQUESTED, `paymentRequestedAt` 기록 = ORD-008 게이트 + 타임아웃 앵커)
- **동기 게이트의 실제 두 조건(Codex P0/P1)**: `transitionToPaymentRequested` → `Order.markPaymentRequested()` 는 (a) order 가 PENDING(취소 아님)이고 **(b) `reservationConfirmedAt != null`(= `reserved=true` 수신)** 일 때만 전이, 아니면 throw(`Order.java:120,123`). 즉 현재 동기 호출이 **취소-차단 + reserve→pay 순서(ADR-0012 §D3)** 를 *결제 시작 전*에 함께 강제한다. 비동기화하면 두 게이트 모두 Toss 호출 *이후*(order-side) 로 밀려 회귀 → payment-로컬에서 두 조건 모두 복원해야 한다.
- **payment 의 reserved 인지 경로 = 이미 ADR 계약**: ADR-0012 §D4 매트릭스가 `stock.reservation.result` 의 consumer 로 **Order 와 Payment** 둘 다 등재(`payment-svc-stock-result-group`, `0012:72`). 즉 payment 가 예약 결과를 소비해 로컬 `readyForPayment` 를 갖는 건 신규 결정이 아니라 ADR 이 이미 정한 경계 — 본 strangler 에서 선반영.
- `OrderPortAdapter`(order/infrastructure/adapter) = `OrderPort` 유일 구현체. order src/main 이 payment 를 참조하는 **유일** 지점(`grep` 확인).
- payment src/main → order 참조: **0건**(payment 는 인터페이스만 보유, order 가 구현 주입). 즉 컴파일 결합은 order→payment(adapter)·런타임 빈 결합은 payment→order. 두 방향 모두 제거 대상.
- **`order.created` payload 는 `userId` 포함**(`OrderOutboxEventPublisher:42` `order.getUserId()`, `OrderCreatedPayload`). Payment 는 이미 `order.created` 를 소비해 `Payment(orderId, totalAmount)` 생성(`PaymentEventConsumer:31`). → `verifyOrderOwner` 를 payment-로컬로 옮길 데이터가 이미 흐른다.
- **`Payment` 엔티티에 `userId` 없음**(`Payment.create(orderId, amount)`). 로컬 소유자 검증하려면 컬럼 추가 필요(Flyway).
- **`PaymentStatus`**: PENDING → APPROVED/FAILED. (취소된 주문에 결제 차단용 로컬 종료 상태 필요 — §게이트 재배치 참고)
- **payload DTO 물리 위치**: `common/src/main/.../global/outbox/dto/` (OrderCreatedPayload·PaymentCompletedPayload 등 9종, B5 단일 소유). 신규 `PaymentRequestedPayload` 도 여기.
- **Flyway**: `common/src/main/resources/db/migration/` V1~V9. 신규 = **V10**(payments user_id/version/ready_for_payment)·**V11**(orders payment_requested_pending marker)·**V12**(payment_cancellations 테이블). 후속 user_id NOT NULL = **V13+**.
- **주문 취소 발행 커버리지(payment-로컬 게이트 누수 점검)**: `order.cancelled` 발행 지점 = `OrderCommandService:96/113`(사용자 취소·만료 취소), `OrderEventConsumer:101`(재고 예약 실패). **결제 *이전* 취소 경로(재고 실패·타임아웃)는 모두 `order.cancelled` 발행 확인** → payment 가 `order.cancelled` 소비로 로컬 게이트 구성 시 누수 없음. (단, `OrderEventConsumer:69` payment.failed→cancel 은 발행 없음 — 이는 결제 *이후* 라 게이트 무관.)

### 설계 — 두 seam 처분

**Seam 1 — `verifyOrderOwner` (읽기, 2곳) → payment-로컬 `userId`.**
Payment 는 이미 `order.created`(userId 포함)로 1:1 생성된다. `Payment` 에 `userId` 컬럼을 더하고(V10), `Payment.create(orderId, userId, amount)` + `PaymentEventConsumer` 가 payload 의 userId 주입. `verifyOrderOwner(userId, orderId)` → payment 자기 행의 userId 비교(`payment.belongsTo(userId)`)로 대체. 새 테이블/캐시 불요(1:1). **새 실패모드 없음**: payment 미존재 시 기존대로 PAY-003.

**Seam 2 — `transitionToPaymentRequested` (쓰기, 1곳) → `payment.requested` 이벤트 + order 가드 소비.**
- Payment 가 `confirmPayment` 시작 시 신규 **`payment.requested`**(payload: orderId, userId; aggregateType PAYMENT; 파티션 키 orderId) 를 outbox 로 발행(`PaymentOutboxEventPublisher.publishPaymentRequested`).
- Order 가 `payment.requested` 소비(신규 리스너, group `order-svc-payment-requested-group`) → `order.markPaymentRequested()` **가드**: PENDING 이면 전이+`paymentRequestedAt` 기록, 종료상태(CANCELLED)면 **no-op**(`stock.reservation.result` cancel-before 선례 동형). 타임아웃 앵커 의미(strangler-3) 보존.

**ORD-008 + reserve→pay 게이트 재배치 (동기 throw → payment-로컬, Codex P0/P1#2).**
동기 `markPaymentRequested` 는 (a) 미취소 + (b) reserved=true 를 결제 *시작 전* 함께 강제했다. 비동기화 시 두 게이트를 payment-로컬로 복원한다:
- **(b) reserve→pay**: Payment 가 `stock.reservation.result` 소비(신규 리스너, group `payment-svc-stock-result-group` — ADR-0012 §D4 기등재) → `reserved=true` 면 로컬 `Payment.markReadyForPayment()`. `confirmPayment` 는 로컬이 ready 아니면 거부(reserved 미수신/false). → reserve→pay 순서 보존.
- **(a) 취소 차단**: Payment 가 `order.cancelled` 소비(신규 리스너, group `payment-svc-order-cancelled-group`) → 로컬 `Payment` 종료 처리. `confirmPayment` 는 로컬 Payment 가 종료상태면 거부. 결제-이전 취소(재고 실패·타임아웃)는 모두 `order.cancelled` 발행(위 커버리지)이라 **선수신 케이스**는 누수 없음.
- **비동기 잔여 창(Codex P0 — 완전 봉합 불가, 명시 수용)**: 게이트 통과 후 Toss 호출 *전* 에 타임아웃/수동취소가 먼저 커밋되면 payment-로컬은 아직 ready/PENDING 이라 Toss 호출이 가능하다(consumer lag). 이 잔여 race 는 동기 락 없이는 닫히지 않으므로, **ADR-0012 §D3 ④ 가 이미 정의한 보상 경로(결제 승인됐으나 주문 종료 → 환불 요청 + 운영 알림, `order.cancelled`/환불 트리거로 수렴)** 로 종결한다. 즉 payment-로컬 게이트는 **흔한 경로(예약 미확정·선취소)를 차단**하고, 드문 lag race 는 보상으로 수렴 — "과금-후-취소 회귀 0" 은 보장하지 않고 **수렴(eventually consistent + 환불)** 으로 약화한다.
- **로컬 종료 상태 + 동시성(트레이드오프, 2차 P1#3)**: 기존 `PaymentStatus.FAILED` 재사용 vs 신규 `CANCELLED`. FAILED 재사용은 Toss-실패 FAILED 와 의미 혼동 + `payment.failed` 미발행 보장 필요 → **신규 `CANCELLED`(이벤트 미발행, 로컬 전용)** 권장. 단 현재 `Payment` 는 `@Version`/CAS 부재(`Payment.java:23-47`)·`PaymentStatus` 는 PENDING→APPROVED/FAILED 만(`PaymentStatus.java`) → Toss approve tx 와 `order.cancelled`→CANCELLED tx 의 **last-write-wins 경합**. → 상태머신을 닫아 쓴다(P4): `cancelBeforePayment()` 는 PENDING/ready 전용, APPROVED/FAILED no-op+보상, 전이는 `@Version`/CAS 보호. 최종 메커니즘 /work.

### B1 — 역의존 스윕 (seam 인바운드 처분)
src/main 인바운드(아래) + src/test 인바운드. 본 PR 은 코드 이동 없음 → "삭제/재배선" 처분.

| # | 인바운드 | 사용 | 처분 |
|---|---|---|---|
| 1 | `payment/application/PaymentCommandService` | `verifyOrderOwner`·`transitionToPaymentRequested` | **재배선** → 로컬 userId 게이트 + `payment.requested` 발행. OrderPort 의존 제거 |
| 2 | `payment/application/PaymentQueryService` | `verifyOrderOwner` | **재배선** → 로컬 userId 게이트. OrderPort 의존 제거 |
| 3 | `order/infrastructure/adapter/OrderPortAdapter` | `OrderPort` 유일 구현 | **삭제** (seam 소멸 후 불요) |
| 4 | `payment/application/port/OrderPort` | 인터페이스 | **삭제** |
| 5 | `payment/application/PaymentCommandServiceTest`·`PaymentQueryServiceTest` | `OrderPort` mock | **재작성** → 로컬 게이트·`payment.requested` 발행·`order.cancelled` 게이트 검증, OrderPort mock 제거 |

> **B1 증거(Codex P2#5) — 인바운드 스윕 명령**: `rg -n "OrderPort|OrderPortAdapter|com\.peekcart\.payment\.application\.port\.OrderPort" src common` 로 전 참조를 뽑아 위 5행에 모두 매핑됨을 확인(현재 grep 결과: src/main 3 call sites[PaymentCommandService×2, PaymentQueryService×1] + adapter 1 + interface 1 + src/test 2[PaymentCommandServiceTest, PaymentQueryServiceTest], 그 외 0). 처분 후 재실행 → OrderPort 잔존 0.
> **검증 강제**: 처분 후 `grep -rn "com\.peekcart\.order" src/.../payment` **및** `grep -rn "com\.peekcart\.payment" src/.../order` → **둘 다 0건**(테스트 포함). 신규 가드 `assertNoOrderPaymentSourceCoupling` 로 회귀 봉쇄.

### B1b — string-level 결합
본 PR 은 도메인 endpoint·캐시·락 이름을 옮기지 않는다(peel 아님). 신규 식별자 = 토픽 `payment.requested` + consumer group 2종(`order-svc-payment-requested-group`·`payment-svc-order-cancelled-group`) — 신규 도입이라 기존 string 결합 없음. ADR-0012 §D4 매트릭스에 등재(P7)해 SSOT 일치.

### B2 — ADR 타깃 ≠ 현재 코드
ADR-0012 §D4 매트릭스(6토픽 SSOT)에 `payment.requested` **없음** + 코드에도 없음(`grep` 0건). → 토픽/DTO/consumer/발행을 **명시 작업항목으로 생성**(P3~P5) + ADR-0012 refine(P7). ORD-008 게이트의 payment-로컬 형태도 현재 코드에 없음 → P4 에서 신설.

### B3 — 공유 테스트 인프라 소유처
본 PR 은 fixture/test-config 를 모듈 간 이동시키지 않음(peel 아님). 신규 테스트는 root `src/test` 에 둠(모놀리스 유지). N/A.

### B5 — 공유 리소스 물리 위치
- Flyway **V10**(payments.user_id) → `common/src/main/resources/db/migration/`(단일 소유, 모든 소비자 도달). `payments` 는 V1 정의(공유 스키마).
- `PaymentRequestedPayload` → `common/.../global/outbox/dto/`(기존 9 payload 와 동거).

### B6 / B8 — peel 전용(본 PR 해당 없음, PR-B 이연)
새 서비스 모듈 생성·`:common` 스캔 횡단 빈·producer 전속 outbox/idempotency/ShedLock 복제·공유 DB poller 소유권 분리는 **모듈 peel(PR-B)** 의 관심사. 본 PR 은 모놀리스 내부라 global.{outbox,idempotency,config} 가 root 에 그대로 — `payment.requested` 발행도 기존 root outbox poller 가 처리. PR-B 계획서 입력으로 명시.

### B7 — N/A
버전 가드 upsert/read-model 없음. `Payment.userId` 는 `order.created` 에서 set-once, 로컬 게이트는 상태 플래그 — 비교/병합 upsert 아님.

### 트레이드오프
- **eventual consistency 창 + 보상 수용(Codex P0)**: `payment.requested` 발행→order 전이 사이 비동기 창 발생(기존 동기 전이 대비). `paymentRequestedAt` 타임아웃 15분이라 앵커는 무해. ORD-008·reserve→pay 게이트는 payment-로컬로 흔한 경로를 차단하나, **게이트 통과↔Toss 사이 lag race 의 과금-후-취소는 완전 봉합 불가** → ADR-0012 §D3 ④ 환불+운영 알림으로 수렴(미결 상태 잔류 없음). "회귀 0" 이 아니라 "수렴" 으로 명시.
- **토픽/관측 표면 +1**: `payment.requested` 토픽 1개 + consumer group 2개 증가(ADR-0012 §107 의 "표면 증가" 수용 기조 일관).
- **Payment 가 order.cancelled 소비**: payment 의 책임이 늘지만, peel 후 payment-service 가 주문 취소를 알아야 하는 건 자연스러운 경계(선반영).

## 3. 작업 항목

- [ ] **P1.** Flyway **expand-contract 2단계**(Codex 1차 P1#4 + 2차 P1#1 — 배포 순서 안전): 현재 consumer 는 `Payment.create(orderId, amount)`(userId 없음)이고 V1 `payments` 에 `user_id` 컬럼 부재 → 한 마이그레이션에서 NOT NULL 까지 가면 구버전 consumer insert 실패.
  - **V10**(`V10__payments_user_id.sql`): `payments.user_id BIGINT` **nullable** 추가 + `orders` 조인 backfill(`UPDATE payments p JOIN orders o ON p.order_id=o.id SET p.user_id=o.user_id`) + `user_id IS NULL` 모니터링. `Payment` 엔티티에 `userId`(nullable 허용) + `Payment.create(orderId, userId, amount)` + `belongsTo(userId)`.
  - **user_id NOT NULL = 후속/운영 게이트로 분리(3차 P1#2 — PR 경계 고정, Flyway 순차)**: NOT NULL 전환 마이그레이션은 **본 strangler PR 범위 밖**이며 marker 들 다음 번호(**V13+**)로 둔다(V11/V12 예약 금지 — out-of-order 회피). 본 PR 완료 = V10(nullable)+backfill+코드+`user_id IS NULL` 모니터링까지. 코드 배포 후 신규 `order.created` 가 전부 userId 채우고 consumer lag 0·null 0건 확인된 뒤 **별도 PR/운영 게이트**로 적용. → 본 PR 은 single-deploy 로 닫힘.
  - `docs/05-data-design.md`(§317-327 Payment DB)에 `user_id` 반영(/done 또는 본 PR).
- [ ] **P2.** Seam 1 제거: `PaymentEventConsumer.handleOrderCreated` 가 payload 의 `userId` 를 `Payment.create` 에 전달. `PaymentCommandService:40`·`PaymentQueryService:28` 의 `orderPort.verifyOrderOwner(...)` → payment-로컬 `payment.belongsTo(userId)` 검증(불일치 시 기존 소유자 에러코드)으로 교체.
- [ ] **P3.** `payment.requested` 발행 + **topic/DLQ 선언(3차 P1#3)**: `PaymentRequestedPayload`(orderId, userId) DTO 신설(`common/.../outbox/dto/`) + `PaymentOutboxEventPublisher.publishPaymentRequested(payment)`(aggregateType `PAYMENT`, eventType `payment.requested`, 파티션 키 orderId). `PaymentCommandService.confirmPayment` 의 `orderPort.transitionToPaymentRequested(...)` 호출을 이 발행으로 교체. **`KafkaConfig`(`KafkaConfig.java:33-58` main topic·`:65-90` DLQ 패턴)에 `payment.requested` + `payment.requested.dlq` `NewTopic`/`TopicBuilder` 추가** — 없으면 로컬/테스트 토픽 미생성.
- [ ] **P4.** Seam 2 order-side + 게이트 2조건 payment-로컬 복원(Codex 1차 P0/P1#2):
  - Order: `OrderEventConsumer` 에 `payment.requested` 리스너(group `order-svc-payment-requested-group`, IdempotencyChecker).
  - **선도착 수렴 + marker 영속(2차 P1#2 · 3차 P1#1)**: `markPaymentRequested()` 는 `reservationConfirmedAt==null` 이면 ORD-008 throw(`Order.java:123`) → DLQ 직행 시 결제는 시작됐는데 order 앵커 전이 유실. Order 의 `stock.reservation.result` 소비는 별도 group 이라 `payment.requested` 가 **선도착** 가능. → 선도착 시 **DLQ 로 보내지 말고** order 로컬 pending marker 기록. **marker 는 Order 엔티티에 영속**: orders Flyway 컬럼 추가(`payment_requested_pending BOOLEAN`, **V11**) + `Order` 필드/`markPaymentRequestedPending()` 메서드 + `confirmReservation()` 내부에서 pending 이면 `markPaymentRequested()` 수렴 + **정리 규칙**(CANCELLED / `reserved=false` 시 marker clear, no-op). 현재 Order 필드는 `reservationConfirmedAt`/`paymentRequestedAt` 뿐(`Order.java:55-59`)이라 신규 필드 필수. (재시도 토픽/지연 재시도 대안 — 최종 메커니즘 /work, Codex 입력.)
  - Payment **reserve→pay 게이트**: `PaymentEventConsumer` 에 `stock.reservation.result` 리스너(group `payment-svc-stock-result-group`, ADR-0012 §D4 기등재, IdempotencyChecker) → `reserved=true` 면 로컬 `Payment.markReadyForPayment()`. `confirmPayment` 가 ready 아니면 거부(reserved 미수신/false).
  - Payment **취소 게이트 + 상태머신 닫기(2차 P1#3)**: `PaymentEventConsumer` 에 `order.cancelled` 리스너(group `payment-svc-order-cancelled-group`, IdempotencyChecker) → Payment 존재 시 `Payment.cancelBeforePayment()`. **상태머신 동시성**: `cancelBeforePayment()` 는 **PENDING/ready 에서만** 성공(신규 `CANCELLED`, 이벤트 미발행), **APPROVED/FAILED 에서는 no-op + 보상 트리거**(덮어쓰기 금지). `approve`/`fail`/`cancelBeforePayment` 는 **`@Version`** 으로 보호 → Toss approve tx ↔ order.cancelled tx 의 last-write-wins 차단.
  - **선도착(Payment 미존재) 누수 0 — 영속 cancellation marker(work GW-2 loop2 P1#1)**: `order.cancelled` 가 `order.created` 보다 선도착하면 throw-재시도만으로는 DLQ 초과 시 누수(silent charge) 가능. → `payment_cancellations`(orderId PK, **V12**) 테이블 + `PaymentCancellation` 엔티티/리포지토리. `order.cancelled` 선도착 시 marker 영속, `handleOrderCreated` 가 Payment 생성 직후 marker 있으면 즉시 `cancelBeforePayment()`(CANCELLED 생성)+marker 삭제. DLQ 로 빠져도 취소가 영구 보존 → confirm 차단.
  - **잔여 race 보상**: `APPROVED 후 order.cancelled`(과금-후-취소)는 로컬 CANCELLED 로 덮지 않고 **APPROVED 유지 + ADR-0012 §D3 ④ 환불+운영 알림** 으로 수렴(본 PR 은 보상 경로 연결만, 환불 구현은 ④).
- [ ] **P5.** seam 삭제: `payment/application/port/OrderPort.java` + `order/infrastructure/adapter/OrderPortAdapter.java` 삭제. `PaymentCommandService`/`PaymentQueryService` 의 `OrderPort` 필드·import 제거. `grep` 으로 order↔payment src/main 상호 참조 0건 확인.
- [ ] **P6.** 신규 가드 `assertNoOrderPaymentSourceCoupling`(root `build.gradle` — `assertNoOrderProductSourceCoupling`(`build.gradle:283`) 미러: src/main 에서 order 가 `com.peekcart.payment` 또는 payment 가 `com.peekcart.order` 포함 시 fail) + aggregate check(`build.gradle:312`)에 `dependsOn` 추가.
- [ ] **P7.** ADR-0012 §D4 refine(GP-1 + Codex P1#3): (a) 신규 행 `payment.requested | Payment | Order | order-svc-payment-requested-group | orderId`. (b) **기존 `order.cancelled` 행에 Payment consumer 추가**(ellipsis 금지 — ADR SSOT 정확성, 2차 P2#4) → `order.cancelled | Order | Product, Payment, Notification | product-svc-order-cancelled-group, payment-svc-order-cancelled-group, notification-svc-order-cancelled-group | orderId`(현재 Product·Notification 만, `0012:75`). (c) refine 노트: ORD-008+reserve→pay 게이트가 동기 `markPaymentRequested` 에서 **payment-로컬(`stock.reservation.result`+`order.cancelled` 소비)** 로 이전, reserve→pay 순서 불변, 잔여 lag race 는 §D3 ④ 보상 수렴(ADR-0012 §78 형식). (d) `02-architecture.md §5`(:97-100) 토픽 수 6→7 + Payment 의 `order.cancelled`/`stock.reservation.result` 소비 동기화는 /done.
- [ ] **P8.** 테스트(Codex P1#2·P2#6):
  - 재작성: `PaymentCommandServiceTest`·`PaymentQueryServiceTest`(OrderPort mock 제거 → 로컬 userId 게이트·`payment.requested` 발행 검증).
  - **reserve→pay 게이트(ADR-0012 §D3)**: `reserved` 미수신 → confirm 거부, `reserved=false` → confirm 거부, `reserved=true` 수신 후 → confirm 허용.
  - **취소 게이트**: `order.cancelled` 선수신 → confirm 거부(Toss 미호출), 정상(미취소+ready) → 통과.
  - 신규: order-side `payment.requested` 소비(PENDING 전이 + 종료상태 no-op), payment-side `stock.reservation.result`/`order.cancelled` 소비(로컬 ready/종료) — 단위/슬라이스.
  - **멱등/순서(1차 P2#6)**: `payment.requested` 동일 eventId 2회→전이 1회; `order.cancelled` 중복 소비 멱등; `payment.requested` 발행 후 `order.cancelled` 지연/역전 도착; DLQ 재발행 동일 eventId 처리.
  - **선도착 수렴 + marker 영속(2차 P1#2·3차 P1#1)**: Order 가 `reservationConfirmedAt==null` 인데 `payment.requested` 선도착 → DLQ 아님, **DB 영속 marker**(orders V11 컬럼) 후 `stock.reservation.result(reserved=true)` 도착 시 `PAYMENT_REQUESTED` 수렴; `reserved=false`/CANCELLED 시 marker clear.
  - **payment-side 취소 marker(work GW-2 loop2 P1#1)**: `order.cancelled` 선도착(Payment 미존재) → `payment_cancellations`(V12) 영속, `order.created` 가 Payment 생성 직후 CANCELLED 적용 → confirm 차단(DLQ 초과 시에도 누수 0).
  - **상태머신 동시성(2차 P1#3)**: APPROVED 후 `order.cancelled` 수신 → CANCELLED 로 안 덮음(APPROVED 유지 + 보상 트리거); PENDING 에서 `order.cancelled` → CANCELLED; CAS/@Version 으로 approve↔cancel 경합 시 1전이만 확정.
  - 보강: `PaymentOutboxEventPublisherTest` 에 `publishPaymentRequested` 케이스.
  - 회귀 확인: `OrderTimeoutSchedulerTest`·`OrderExpiredPaymentRequestedQueryIntegrationTest`(`paymentRequestedAt` 기반 — 시드를 동기 호출이 아니라 도메인/이벤트로 세팅하도록 필요 시 조정), `OrderCommandServiceTest`. 그린 유지.

## 4. 영향 파일

**신규(본 PR)**: `common/.../db/migration/V10__payments_user_id.sql`(nullable+backfill) · `V11__orders_payment_requested_pending.sql`(order pending marker, 3차 P1#1) · `V12__payment_cancellations.sql`(payment-side 취소 marker, work GW-2 loop2 P1#1) · `common/.../global/outbox/dto/PaymentRequestedPayload.java` · `payment/domain/model/PaymentCancellation.java` + `payment/domain/repository/PaymentCancellationRepository.java` + `payment/infrastructure/PaymentCancellation{JpaRepository,RepositoryImpl}.java` · order-side `payment.requested` 선도착/수렴 소비 테스트 · `PaymentEventConsumerTest`.
**후속(본 PR 밖)**: `V13+__payments_user_id_not_null.sql`(코드 배포·lag 0·null 0 확인 후 별도 PR/운영 게이트).
**수정**: `order/domain/model/Order.java`(pending marker 필드+`markPaymentRequestedPending()`+`confirmReservation()` 수렴·정리, 3차 P1#1) · `global/config/KafkaConfig.java`(`payment.requested`/`payment.requested.dlq` NewTopic, 3차 P1#3) · `payment/domain/model/Payment.java`(userId·belongsTo·create 시그니처·`readyForPayment`·`cancelBeforePayment`·CANCELLED 종료·**`@Version`/CAS**) · `payment/domain/model/PaymentStatus.java`(CANCELLED 채택 시) · `payment/infrastructure/kafka/PaymentEventConsumer.java`(userId 주입 + `stock.reservation.result` 리스너 + `order.cancelled` 리스너 + 취소 marker 영속/적용 + `SlackPort` 보상 알림) · `payment/infrastructure/outbox/PaymentOutboxEventPublisher.java`(publishPaymentRequested) · `payment/application/PaymentCommandService.java`(reserve→pay+취소 로컬 게이트·발행·OrderPort 제거) · `payment/application/PaymentQueryService.java`(로컬 게이트·OrderPort 제거) · `order/infrastructure/kafka/OrderEventConsumer.java`(`payment.requested` 리스너) · root `build.gradle`(`assertNoOrderPaymentSourceCoupling` + aggregate dependsOn) · `docs/adr/0012-phase4-db-event-saga-contract.md`(§D4 payment.requested 행 + order.cancelled 행 Payment consumer + 게이트 refine 노트) · `docs/05-data-design.md`(payments.user_id) · 테스트(P8).
**삭제**: `payment/application/port/OrderPort.java` · `order/infrastructure/adapter/OrderPortAdapter.java`.
**불변**: order/payment 외 도메인 · global.{outbox,idempotency,config}(PR-B 이연) · `OrderCreatedPayload`(userId 이미 보유).

## 5. 검증 방법
- `grep -rn "com\.peekcart\.payment" src/main/java/com/peekcart/order` → **0건**; `grep -rn "com\.peekcart\.order" src/main/java/com/peekcart/payment` → **0건**(테스트 포함 전체 `src` 도 확인).
- `./gradlew assertNoOrderPaymentSourceCoupling assertNoServiceProjectDeps assertNoDuplicateGlobalFqcn assertNoOrderProductSourceCoupling` → BUILD SUCCESSFUL.
- 게이트 회귀: (a) reserved=true·미취소 주문 → `confirmPayment` 통과 + `payment.requested` 발행 → order `PAYMENT_REQUESTED` 전이 + `paymentRequestedAt` 기록. (b) `reserved` 미수신/`false` → `confirmPayment` 거부(reserve→pay, ADR-0012 §D3). (c) `order.cancelled` 선수신 → `confirmPayment` 거부, Toss 미호출.
- `payment.requested`·`order.cancelled` 소비 멱등(동일 eventId 2회 → 1회), 종료 주문 `payment.requested` 수신 시 no-op, 이벤트 역전/지연 도착에도 게이트 불변.
- `payment.requested`/`payment.requested.dlq` 토픽이 `KafkaConfig` 로 생성됨(로컬/테스트), DLQ 라우팅 회귀.
- 선도착 시 order pending marker 가 **DB 에 영속**(orders V11)되고, `stock.reservation.result(reserved=true)` 도착 시 `PAYMENT_REQUESTED` 로 수렴. `order.cancelled` 선도착은 `payment_cancellations`(V12)에 영속돼 Payment 생성 시 CANCELLED 적용.
- `./gradlew build test` 전체 그린(root 6모듈).

## 6. 완료 조건
- Order↔Payment src/main 동기 결합(빈/import) 0건 — `OrderPort`/`OrderPortAdapter` 삭제, payment 는 로컬 userId + 이벤트만 사용. 신규 가드 통과(회귀 봉쇄).
- ORD-008(취소 주문 차단) + reserve→pay(reserved=true 전 차단, ADR-0012 §D3) 게이트를 payment-로컬로 복원 + `paymentRequestedAt` 타임아웃 앵커 보존(`payment.requested` 이벤트). 잔여 lag race 는 §D3 ④ 보상 수렴으로 명시(회귀 0 아님).
- payments.user_id 는 expand-contract 로 무중단: **본 PR 완료 = V10(nullable)+backfill+코드+null 모니터링까지**(single-deploy). V11 NOT NULL 은 lag 0·null 0 확인 후 후속 PR/운영 게이트(본 PR 완료조건 아님).
- order pending marker(`payment.requested` 선도착)는 Order 엔티티+Flyway(V11)로 영속, `confirmReservation()` 에서 수렴, CANCELLED/`reserved=false` 시 정리. `order.cancelled` 선도착은 `payment_cancellations`(V12)로 영속돼 누수 0.
- Payment 상태머신 동시성 닫힘: `cancelBeforePayment` PENDING/ready 전용·APPROVED/FAILED no-op+보상, `@Version`/CAS 로 approve↔cancel 경합 1전이 확정. `payment.requested` 선도착은 order 로컬 수렴(DLQ 유실 0).
- ADR-0012 §D4 에 `payment.requested` 행 + `order.cancelled` 행 Payment consumer(정확한 group 3종) 등재 + 게이트 refine 노트(P7), `02 §5` 토픽 수(6→7) 동기화는 /done.
- 전체 build/test 그린. **모듈 peel·root app 소멸·global infra 재분배는 본 PR 범위 외(PR-B, 본 계획서를 입력으로)** 명시.
