# task-impl-saga3-2phase-payment-gate — 2-phase 예약 확정/해제 + Payment charge 예약 게이트

> 사가 클러스터 strangler-3 (ADR-0010 F2 · ADR-0012 D3/④). 모놀리스 내부 strangler — **module peel 없음**.
> 범위 결정(2026-06-16): **2-phase 확정/해제 + Payment charge 게이트**. `verifyProductExists` 캐시 전환·Product→Order+Payment peel 은 후속 단계로 분리.
> GP-2(Codex 리뷰, 2026-06-16) 반영: ADR-0012 ④ **commit-실패 보상 경로**와 **타임아웃 race** 를 범위에 포함(상세 §audit).
> 새 ADR 불필요 — 경계·6토픽·Saga·보상경로(④)는 ADR-0010/0012 가 이미 결정. CONFIRMED 상태/보상/게이트는 그 구현.

## 1. 목표

예약 원장에 **CONFIRMED 종결 상태**를 추가해 2-phase commit(예약→확정/해제)을 완성하고, **결제 승인이 재고 예약 확정 이후에만** 진행되며, **결제됐으나 재고가 미확정된 최악 경로는 보상(환불 요청+운영 알림)으로 수렴**(미결 잔존 금지)하도록 한다 (ADR-0012 D3/④).

- (commit) `payment.completed` → Product 가 원장을 `RESERVED → CONFIRMED` 로 확정. 확정 후 어떤 release 이벤트가 와도 복구되지 않음(판매분 보호).
- (gate) `confirmPayment` 는 예약 미확정(`reservationConfirmedAt == null`) 주문을 Toss 호출 전에 거부.
- (compensation) confirm 시점에 원장이 RESERVED/CONFIRMED 가 아니면(=결제됐는데 재고가 해제/미예약) **commit-실패**로 분류해 운영 알림+환불 요청+audit 로 수렴 (silent no-op 금지).
- (timeout race) `confirmPayment` 직후 15분 타임아웃이 진행 중 결제를 취소하는 체계적 race 를 `payment_requested_at` 기준으로 제거.

## 2. 배경 / 제약 (현재 코드 검증)

strangler-1/2 까지의 현재 흐름 (직접 grep/read 로 확인):

1. `createOrder` → `PENDING` + `order.created` 발행 (동기 차감 없음, `OrderCommandService.java:51,78`).
2. Product `StockReservationConsumer`(`order.created`) → `reserve()` **즉시 차감** + 원장 `RESERVED` + `stock.reservation.result` 발행.
3. Order `OrderEventConsumer.handleStockReservationResult`: `reserved=true` → `confirmReservation()`(`reservationConfirmedAt` 기록, 상태 PENDING 유지) / `reserved=false` → `cancel()` + `order.cancelled` 발행.
4. `confirmPayment` → `verifyOrderOwner` → `transitionToPaymentRequested`(PENDING→PAYMENT_REQUESTED) → Toss → `payment.completed`/`payment.failed` 발행 (`PaymentCommandService.java:40-60`).
5. Order: `payment.completed`→`PAYMENT_COMPLETED`; `payment.failed`→`cancel()`.
6. Product `StockReleaseConsumer`(`order.cancelled`/`payment.failed`) → `release()` 가 `RESERVED→RELEASED` 원자 CAS 1건 성공 시에만 복구(`markReleasedIfReserved`).
7. 타임아웃: `OrderTimeoutScheduler` — PAYMENT_REQUESTED 가 `orderedAt < now-15m` 이면 `cancelExpiredOrder`→`order.cancelled` 발행(`OrderCommandService.java:114`); 예약 미확정 PENDING 5분 sweep(`reservationConfirmedAt` null 기준).

**검증으로 확인한 갭 (GP-2 보강 포함):**

- **갭 A (확정 부재 + race 미보호)**: 원장 상태는 `RESERVED/CANCEL_REQUESTED/RELEASED/FAILED` 뿐. happy path 결제 성공 후에도 `RESERVED` 잔존 → release CAS 가 여전히 성공 가능. **그리고 CONFIRMED 를 추가해도 "confirm 이 release 보다 먼저 도착"을 보장할 수 없다** — `payment.completed`(confirm)와 `order.cancelled`(release)는 별도 토픽이라 순서 무보장(ADR-0012 D4). 따라서 confirm 단독으로는 race 를 막지 못하고, **confirm 시점에 commit-실패를 검출해 보상**해야 한다(ADR-0012 ④). (Codex GP-2 #1/#2)
- **갭 B (결제 과금 게이트 부재)**: `transitionToPaymentRequested` 는 전이 가능 여부만 검사(`OrderPortAdapter.java:22-27`). in-flight 예약(`reservationConfirmedAt==null`) PENDING 도 결제 통과 → 과금 후 `reserved=false` 도착 시 `PAYMENT_COMPLETED→CANCELLED` 불가로 ORD-003 실패, 과금됐지만 재고 없음.
- **갭 C (타임아웃 race, GP-2 #3)**: `cancelExpiredOrders` 15분 기준이 `orderedAt`. 생성 15분 경과 주문을 결제하면 게이트 통과→PAYMENT_REQUESTED 전이 직후 스케줄러(≤60s)가 `order.cancelled` 발행→Toss 진행 중 release 선성공→`payment.completed` confirm 은 RELEASED 에서 commit-실패. **체계적 race**(드문 게 아니라 지연 결제마다 재현).

**제약 / 전제:**

- 마이그레이션 SSOT 는 `:common` Flyway 단일 소유(B5). 최신 = `V7`. 적용된 마이그레이션 immutable — 신규 컬럼은 `V8`(stock_reservations.confirmed_at)·`V9`(orders.payment_requested_at) 로 추가.
- `status VARCHAR(20)` — enum `CONFIRMED` 추가에 DDL 불필요. confirmed_at 은 transition audit 대칭(reserved_at/released_at) 용 타임스탬프.
- Product 신규 소비: `payment.completed`, consumer group `product-svc-payment-completed-group` (ADR-0012 D4 매트릭스에 이미 명시).
- 보상 실행: 자동 환불 플로우는 **현재 미존재**(Payment 에 refund 없음). `SlackPort.send`(`:common`) 로 운영 알림 + audit + 환불 요청 마킹까지가 본 PR 범위. 자동 환불은 별도 추적. ADR-0012 ④ "환불 요청 + 운영 알림" 의 "요청" 수준 충족.

**영향 표면 (인바운드 스윕 — peel 아님):**

- `release()`/`markReleasedIfReserved` 호출처: `StockReleaseConsumer`(order.cancelled, payment.failed) — 무변경. CONFIRMED 에서 CAS 자연 no-op.
- `transitionToPaymentRequested` 호출처: `confirmPayment` 1건 — 게이트+paymentRequestedAt 적용.
- `confirmReservation()`/`reservationConfirmedAt` 참조처: `OrderEventConsumer`(set), `cancelUnconfirmedReservations`(null sweep), 게이트(신규 read) — 의미 불변.
- `findByStatusAndOrderedAtBefore` 호출처: `cancelExpiredOrders` 1건(`OrderTimeoutScheduler.java:36`) — paymentRequestedAt 기준 쿼리로 교체. (예약 미확정 5분 sweep `findUnconfirmedReservationBefore` 는 무변경)

## 3. 작업 항목

- [ ] **P1.** 원장 CONFIRMED 상태 + `confirmed_at` — (a) `ReservationStatus` 에 `CONFIRMED` + javadoc(결제 성공 확정, 복구 불가). (b) `StockReservation` 에 `@Column(name="confirmed_at") LocalDateTime confirmedAt`. (c) `common/.../db/migration/V8__stock_reservation_confirmed_at.sql` — `ALTER TABLE stock_reservations ADD COLUMN confirmed_at DATETIME(6) NULL`. → verify: 마이그레이션 적용, 빌드 그린.
- [ ] **P2.** `RESERVED → CONFIRMED` 원자 CAS — (a) `StockReservationRepository.markConfirmedIfReserved(Long): int`. (b) `StockReservationJpaRepository` `@Modifying(clearAutomatically=true)` `@Query`(markReleasedIfReserved 미러, `status=CONFIRMED, confirmedAt=:now WHERE orderId=:orderId AND status=RESERVED`). (c) `RepositoryImpl` delegate. → verify: repository 통합테스트(RESERVED 에서만 1건 전이, 비-RESERVED 0건).
- [ ] **P3.** `StockReservationService.confirm(orderId)` — 상태 분기(silent no-op 금지, ADR-0012 ④ 재시도/보상 계약 반영): `markConfirmedIfReserved` 1건 → 확정 완료. CAS 0건이면 현재 원장 조회로 분기 — **CONFIRMED → 멱등 no-op**(중복 payment.completed) · **RELEASED / CANCEL_REQUESTED → 비가역 commit-실패(PAID_BUT_UNRESERVED)** 로 P4 보상 트리거 · **없음 → transient(예약 원장 미도착 경합)로 보고 예외 throw 하여 Kafka consumer 재시도(bounded), 재시도 한계 초과(DLQ) 시 P4 보상**. `release()` 무변경(CONFIRMED no-op). → verify: 단위테스트 confirm CAS happy + CONFIRMED 멱등 + RELEASED/CANCEL_REQUESTED 보상 호출 + 없음 throw(재시도) + release-after-confirm 복구 안 함.
- [ ] **P4.** commit-실패 보상 경로 + 멱등 marker (ADR-0012 ④) — confirm 의 PAID_BUT_UNRESERVED 분기에서 (a) `orderId + reason(PAID_BUT_UNRESERVED)` 유니크 키 보상 marker/audit **upsert**(최초 생성 시에만 후속 알림). DLQ 재발행이 새 eventId 로 와도 `processed_events(eventId,group)` 우회 → marker 유니크 키로 중복 알림 차단. (b) marker 최초 생성 시에만 `SlackPort.send` 운영 알림(orderId·원장상태, 수동 환불 유도) + 구조화 ERROR 로그. 자동 환불 실행은 미존재 → 본 PR 은 **요청/알림+마킹까지**(저장 위치=보상 marker 테이블/원장 audit, 자동 환불 별도 추적 §7). → verify: 단위테스트 PAID_BUT_UNRESERVED 시 marker 1건 생성 + SlackPort.send 1회; 같은 orderId 재처리 시 marker no-op + send 0회.
- [ ] **P5.** Product `payment.completed` 소비 → 확정 — 신규 `product/infrastructure/kafka/StockConfirmConsumer`: `@KafkaListener(topics="payment.completed", groupId="product-svc-payment-completed-group")` + `@Transactional` + `idempotencyChecker.executeIfNew` → `reservationService.confirm(orderId)`. (release 와 의미 분리 위해 별도 consumer). → verify: e2e 통합테스트 payment.completed → 원장 CONFIRMED.
- [ ] **P6.** Payment charge 게이트 + 타임아웃 race fix — (a) `ErrorCode.ORD_008(CONFLICT,"ORD-008","재고 예약이 아직 확정되지 않았습니다. 잠시 후 다시 시도해주세요.")`. (b) `Order.markPaymentRequested()`: `canTransitionTo(PAYMENT_REQUESTED)` 불가 → `ORD-003`(취소/종결 permanent) → `reservationConfirmedAt==null` → `ORD-008`(in-flight retry) → 통과 시 `PAYMENT_REQUESTED` 전이 + `paymentRequestedAt=now` 기록. (c) `OrderPortAdapter.transitionToPaymentRequested` 가 `markPaymentRequested()` 호출로 교체. (d) `Order.paymentRequestedAt` 필드 + `common/.../db/migration/V9__orders_payment_requested_at.sql` — 컬럼 추가 + **backfill** `UPDATE orders SET payment_requested_at = ordered_at WHERE status='PAYMENT_REQUESTED' AND payment_requested_at IS NULL`(기존 in-flight 행이 새 쿼리에서 영구 제외되는 회귀 방지). (e) `OrderTimeoutScheduler.cancelExpiredOrders` 15분 기준을 `orderedAt`→`paymentRequestedAt` 으로 변경 + `OrderRepository.findByStatusAndPaymentRequestedAtBefore` 신규(기존 `findByStatusAndOrderedAtBefore` 호출 0건 되면 제거). 과도기 안전 위해 쿼리는 `paymentRequestedAt < cutoff OR (paymentRequestedAt IS NULL AND orderedAt < cutoff)` 포함 검토. → verify: 게이트 단위/슬라이스(미확정→ORD-008·Toss 미호출, CANCELLED→ORD-003, 확정→통과·paymentRequestedAt set), 타임아웃 쿼리 통합테스트(backfill 행·null 행 모두 cutoff 시 잡힘).
- [ ] **P7.** 테스트 + 문서 — (a) `StockReservationServiceTest`: confirm happy/멱등/보상. (b) 통합테스트: payment.completed→CONFIRMED; **역순 race**(release(order.cancelled) 먼저 처리→RELEASED 후 payment.completed confirm→보상 트리거); **타임아웃 경합**(예약 확정된 오래된 PENDING→confirmPayment→PAYMENT_REQUESTED→paymentRequestedAt 기준이라 즉시 취소 안 됨); confirm CAS 0건(RELEASED 보상 / 없음 재시도); **동시성 경합**(같은 orderId RESERVED 에서 payment.completed confirm 2개 + order.cancelled release 1개 동시 실행 → `CONFIRMED+복구0+보상0` 또는 `RELEASED+복구1+보상1` 한쪽으로만 수렴, 복구/보상 중복 없음). (c) Order 게이트(도메인 `markPaymentRequested` + 결제 경로 ORD-008/ORD-003 분류 슬라이스). (d) `confirmReservation` 멱등 회귀. (e) API 에러 문서(ORD-008 retryable vs ORD-003 permanent) + TASKS.md ①행 strangler-3 ✅ + `phase4-design-roadmap.md §2/§4` + audit. → verify: `./gradlew test` 그린.

## 4. 영향 파일

| 파일 | 변경 |
|---|---|
| `src/.../product/domain/model/ReservationStatus.java` | `CONFIRMED` |
| `src/.../product/domain/model/StockReservation.java` | `confirmedAt` |
| `common/.../db/migration/V8__stock_reservation_confirmed_at.sql` | 신규 |
| `common/.../db/migration/V9__orders_payment_requested_at.sql` | 신규 |
| `src/.../product/domain/repository/StockReservationRepository.java` | `markConfirmedIfReserved` |
| `src/.../product/infrastructure/StockReservationJpaRepository.java` | CONFIRMED CAS |
| `src/.../product/infrastructure/StockReservationRepositoryImpl.java` | delegate |
| `src/.../product/application/StockReservationService.java` | `confirm()` + 보상 분기 |
| `src/.../product/infrastructure/kafka/StockConfirmConsumer.java` | 신규 |
| `common/.../global/exception/ErrorCode.java` | `ORD_008` |
| `src/.../order/domain/model/Order.java` | `markPaymentRequested()` + `paymentRequestedAt` |
| `src/.../order/infrastructure/adapter/OrderPortAdapter.java` | `markPaymentRequested()` 호출 |
| `src/.../order/domain/repository/OrderRepository.java` + `OrderJpaRepository.java` | `findByStatusAndPaymentRequestedAtBefore` |
| `src/.../order/infrastructure/scheduler/OrderTimeoutScheduler.java` | 15분 기준 교체 |
| `src/test/.../product/...` · `src/test/.../order/...` | 단위/통합/게이트/race 테스트 |
| `docs/TASKS.md` · `docs/progress/phase4-design-roadmap.md` · 본 계획 audit · API 에러 문서 | 진행 반영 |

## 5. 비대상 (scope out)

- **`verifyProductExists` 캐시 전환** — 범위 제외. 동기 `ProductPort.verifyProductExists` 1메서드 잔존 유지.
- **module peel / ② DB 분리** — 후속. 공유 outbox/DB/`processed_events` 재사용 모놀리스 내부 strangler.
- **자동 환불 실행** — Payment 에 refund 플로우 미존재. 본 PR 은 보상 = 운영 알림 + 환불 요청 마킹(수동 ops)까지. 자동 환불(Toss cancel API 연동)은 별도 추적.
- **확정-후-미결제 PENDING 장기 점유** — strangler-1(confirmReservation) 이래의 기존 한계. 게이트가 부각하나 만료 타임아웃 신설은 본 범위 밖 → §7 한계로 문서화.

## 6. 이벤트/계약

- **신규 소비**: Product `payment.completed`(group `product-svc-payment-completed-group`, ADR-0012 D4 매트릭스 기존 명시). 신규 토픽 없음.
- **payload**: `orderId` read(confirm CAS 키). 스키마 변경 없음.
- **멱등**: `executeIfNew(eventId, group)` + `markConfirmedIfReserved` CAS 0/1 로 at-least-once 흡수. 중복 payment.completed → 2번째는 CONFIRMED no-op.
- **순서/race**: confirm(payment.completed)와 release(order.cancelled/payment.failed)는 별도 토픽이라 무순서. confirm-우선 가정 금지 — confirm 0건+비CONFIRMED 는 **commit-실패 보상**으로 수렴(ADR-0012 ④). 게이트+paymentRequestedAt 로 정상경로 race 확률 최소화, 잔여는 보상.
- **보상 멱등**: PAID_BUT_UNRESERVED 알림은 `orderId+reason` 유니크 marker upsert 최초 생성 시에만 `SlackPort.send` → `processed_events(eventId,group)` 우회(DLQ 새 eventId) 시에도 중복 알림 차단. `없음` 분기는 throw 로 Kafka 재시도(bounded), 한계 초과(DLQ)만 보상.

## 7. 트레이드오프 / 한계

- **CONFIRMED = 차감 commit, 2차 차감 아님**: 차감-즉시 모델 유지. CONFIRMED 는 release CAS 무력화(판매분 굳힘). soft-hold→확정 시 실차감 2단계는 재고 모델 전면 개편이라 비대상.
- **race 를 막지 않고 검출+보상**: cross-topic 무순서라 confirm-우선을 보장하는 직렬화 지점을 두지 않고, ADR-0012 ④ 대로 commit-실패를 confirm 시점에 검출해 보상으로 수렴. 정상경로 race 는 게이트+paymentRequestedAt 로 최소화. 일시적 불일치(결제완료↔재고 RELEASED) 가능, 보상으로 종결.
- **보상 = 알림/요청까지(자동 환불 미실행)**: 자동 환불 플로우 부재로 운영 알림+환불 요청 마킹까지. ADR-0012 ④ "환불 요청+운영 알림" 의 요청 수준 충족, 자동 실행 별도 추적.
- **`confirmed_at`/`payment_requested_at` 컬럼**: 로직상 status/null 검사로 충분하나 audit 대칭·타임아웃 기준 정확성 위해 추가.
- **확정-후-미결제 재고 점유**(§5): 게이트가 부각하는 기존 한계. 만료 타임아웃 별도 추적.

## 8. 검증 방법

- `./gradlew test` 그린 — 단위(confirm happy/멱등/보상, release-after-confirm 보호, 게이트 ORD-008/003) + 통합(payment.completed→CONFIRMED, **역순 release-before-confirm→보상**, **타임아웃 경합→paymentRequestedAt 기준 안전**, confirm 0건 보상) + repository CAS.
- V8/V9 마이그레이션 적용 확인(confirmed_at·payment_requested_at 컬럼).
- grep: `OrderPortAdapter` 가 `markPaymentRequested()` 경유(generic `transitionTo(PAYMENT_REQUESTED)` 직접 호출 0건), `cancelExpiredOrders` 가 `paymentRequestedAt` 기준(`findByStatusAndOrderedAtBefore` 호출 0건).
- `markConfirmedIfReserved` 가 RESERVED 에서만 1건 전이.

## 9. 완료 조건

- P1~P7 전 항목 체크 + `./gradlew test` 그린.
- 결제 성공 후 중복/지연 release 가 와도 (CONFIRMED 선행 시) 재고 복구 안 됨; (release 선행 시) commit-실패 보상(운영 알림)이 발화하고 미결 잔존 없음 — ADR-0012 ④ 충족.
- 예약 미확정 주문의 `confirmPayment` 가 Toss 호출 전 ORD-008 거부; 종결 주문은 ORD-003.
- 지연 결제(생성 15분 경과)가 paymentRequestedAt 기준이라 결제 진행 중 타임아웃 취소되지 않음.
- TASKS.md ①행·`phase4-design-roadmap.md` 갱신 + audit + API 에러 분류 문서.

## Audit Log

### 2026-06-16 — GP-2 (loop 1)
- 리뷰 항목: 5건 (P0:2, P1:2, P2:1)
- 사용자 선택: [2] 전체 반영 (P0+P1+P2)
- 반영 내용:
  - **P0 #2 (ADR-0012 ④ commit-실패 보상 누락)** → 갭 A 재서술 + P3 상태 분기(silent no-op 금지) + P4 보상 경로(SlackPort 알림+audit+환불요청) 신설.
  - **P0 #1 (cross-topic release-before-confirm)** → confirm-우선 가정 제거, §6/§7 무순서 명문화 + 보상으로 수렴(#2 와 동일 뿌리). Codex 의 payment.failed 예시는 동일 주문 상호배타로 부정확하나 order.cancelled(타임아웃) 경로로 클래스 유효 — 검증 완료.
  - **P1 #3 (타임아웃 race)** → 갭 C 추가 + P6 에 `payment_requested_at`(V9) + 스케줄러 15분 기준 orderedAt→paymentRequestedAt 전환.
  - **P1 #4 (검증 미흡)** → P7 에 역순 race·타임아웃 경합·confirm 0건 보상 테스트 추가.
  - **P2 #5 (ORD 분류)** → P6/P7 에 ORD-008 retryable vs ORD-003 permanent 슬라이스 테스트 + API 에러 문서.
- raw: .cache/codex-reviews/plan-task-impl-saga3-2phase-payment-gate-1781633977.json
- run_id: plan:20260616T181913Z:b535ec5c-a9a1-4080-b780-39859542eb1a:1

### 2026-06-16 — GP-2 (loop 2, 재리뷰)
- 리뷰 항목: 4건 (P0:0, P1:3, P2:1) — 1차 P0/P1 방향(보상 수렴·paymentRequestedAt 전환) 적절 확인
- 사용자 선택: [1] 전체 반영 후 종료
- 반영 내용:
  - **P1 #1 (V9 backfill 회귀)** → P6(d) 에 기존 PAYMENT_REQUESTED 행 backfill `UPDATE` + 과도기 쿼리(null 폴백) 추가.
  - **P1 #2 (confirm retry 의미)** → P3 분기를 `CONFIRMED=no-op / RELEASED·CANCEL_REQUESTED=비가역 보상 / 없음=bounded retry(throw→Kafka/DLQ) 후 보상` 으로 세분(ADR-0012 ④ 정합).
  - **P1 #3 (보상 멱등 키)** → P4 에 `orderId+reason` 유니크 marker upsert, 최초 생성 시만 SlackPort.send. §6 멱등 보강.
  - **P2 #4 (동시성 테스트)** → P7(b) 에 confirm×2+release×1 동시 경합 수렴 통합테스트 추가.
- raw: .cache/codex-reviews/plan-task-impl-saga3-2phase-payment-gate-1781634551.json
- run_id: plan:20260616T182839Z:b535ec5c-a9a1-4080-b780-39859542eb1a:2
