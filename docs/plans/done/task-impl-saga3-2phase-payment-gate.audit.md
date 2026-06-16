# task-impl-saga3-2phase-payment-gate — 구현 audit

> strangler-3: 2-phase 예약 확정/해제 + Payment charge 예약 게이트 (ADR-0010 F2 · ADR-0012 D3/④).
> 모놀리스 내부 strangler — module peel 없음. 계획서: `task-impl-saga3-2phase-payment-gate.md`.

## 구현 요약 (P1~P7)

- **P1** 원장 `CONFIRMED` 상태 + `confirmed_at`/`compensated_at` 컬럼(V8). `status VARCHAR(20)` 라 enum 값 추가는 DDL 불요, 타임스탬프 2개만 ALTER.
- **P2** `markConfirmedIfReserved`(RESERVED→CONFIRMED) + `markCompensatedIfAbsent`(보상 1회성) 원자 CAS — `markReleasedIfReserved` 미러.
- **P3** `StockReservationService.confirm(orderId)` 상태 분기: RESERVED→CONFIRMED commit / CONFIRMED 멱등 no-op / **없음=transient → 예외 throw(consumer 재시도)** / RELEASED·CANCEL_REQUESTED·FAILED → 보상.
- **P4** commit-실패(PAID_BUT_UNRESERVED) 보상 — `compensated_at` orderId 1회성 marker 로 멱등(DLQ 새 eventId 재실행에도 중복 알림 차단), 최초 1회 `SlackPort.send` + ERROR 로그. 자동 환불 미존재 → 운영 알림+마킹까지(수동 환불).
- **P5** `StockConfirmConsumer`(`payment.completed`, group `product-svc-payment-completed-group`) → confirm. release 와 의미 분리 위해 별도 consumer.
- **P6** 게이트 + 타임아웃 race fix — `Order.markPaymentRequested()`(전이불가→ORD-003, 미확정→ORD-008, 통과 시 PAYMENT_REQUESTED + `paymentRequestedAt` 기록), `OrderPortAdapter` 교체, `payment_requested_at`(V9 + backfill), 스케줄러 15분 기준 `orderedAt`→`paymentRequestedAt`(+ null 폴백 쿼리 `findExpiredPaymentRequested`).
- **P7** 테스트 + 본 audit.

## ORD 에러 분류 (API 계약 = HttpStatus)

| 코드 | HttpStatus | 의미 | 클라이언트 처리 |
|---|---|---|---|
| `ORD-008` | 409 CONFLICT | 예약 미확정(in-flight) — 결제 게이트 차단 | **retryable** — 잠시 후 재시도 |
| `ORD-003` | 400 BAD_REQUEST | 취소/종결 주문의 전이 불가 | **permanent** — 재시도 무의미 |

게이트는 전이 가능 여부(ORD-003)를 먼저 검사하고, 그 다음 예약 확정(ORD-008)을 검사한다 → 취소된 주문은 ORD-003(영구), 예약 진행 중 주문은 ORD-008(재시도)로 명확히 구분. 슬라이스 테스트(`OrderTest`, `PaymentCommandServiceTest`)로 강제.

## 계획 대비 편차

- **보상 marker 저장 위치**: 계획 §6 은 "marker 테이블/원장 audit" 둘 중 택일을 열어뒀고, 구현은 **신규 테이블 대신 원장 `compensated_at` 컬럼 + CAS** 채택. orderId 가 곧 멱등 키이고 원장 row 가 이미 존재(RELEASED/CANCEL_REQUESTED)하므로 별도 테이블·엔티티 없이 1회성 보장. 더 단순.
- **FAILED 상태도 보상 대상**에 포함: 계획은 RELEASED/CANCEL_REQUESTED/없음을 명시했으나, payment.completed 시점 원장이 FAILED(재고부족 예약실패)인 것도 "결제됐으나 재고 미확정" 이므로 동일 보상 분기로 수렴(없음만 transient throw, 나머지 row 존재 비-CONFIRMED 는 보상).

## 검증

- 단위: `StockReservationServiceTest`(confirm happy/멱등/없음 throw/RELEASED 보상/보상 멱등/release-after-confirm 보호), `OrderTest`(게이트 ORD-008/ORD-003/성공), `OrderTimeoutSchedulerTest`(finder 교체), `PaymentCommandServiceTest`(게이트 전파·Toss 미호출) — **그린**.
- 통합(Testcontainers): `StockReservationSagaIntegrationTest` — confirm·release-after-confirm 보호·**역순 race 보상**·보상 멱등·없음 throw·**confirm×2+release×1 동시성 수렴(복구/보상 무중복)** — **그린**. `product-svc-payment-completed-group` consumer 정상 기동 확인.
- V8/V9 마이그레이션 적용 + backfill 포함.
- 전체 루트 `:test` 스위트 그린(회귀 없음).

## GW-2 (diff 리뷰, loop 1)
- run: `work:...:1`(timeout 180s, 재시도) → `work:...:2`(ok, tokens 160,306)
- 항목: 3건 (P0:0, P1:0, P2:3) — **자동 통과**(기능 결함 없음), P2 테스트 갭 전체 반영
  - **#1** 보상 단위테스트를 RELEASED/CANCEL_REQUESTED/FAILED 로 `@EnumSource` 파라미터화(최초 1회 알림 + 멱등).
  - **#2** `findExpiredPaymentRequested` JPQL 통합테스트 신설(`OrderExpiredPaymentRequestedQueryIntegrationTest`) — paymentRequestedAt 과거/최근 + **null 폴백**(orderedAt) + 비-PAYMENT_REQUESTED 제외. mock 이 못 잡는 JPQL/JOIN FETCH 회귀 방어.
  - **#3** `StockConfirmConsumer` Kafka 소비 e2e(envelope→CONFIRMED, 동일 eventId 중복 멱등) — 기존 통합테스트 컨테이너 재사용.
- raw: .cache/codex-reviews/diff-task-impl-saga3-2phase-payment-gate-1781636472.json
