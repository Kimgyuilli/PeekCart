---
grade: M
---
# D-047 — e2e 만 보는 로직 3건을 JVM 테스트로 채운다

## 1. 명제

D-042 결함 주입의 M12·M13·M14 를 다시 넣었을 때 해당 모듈의 JVM 테스트가 **하나라도 통과 상태로
남으면** 미완이다 (see ADR-0032 D3). e2e 단언 A4b·A12·B3 은 배선 때문에 그대로 둔다.

등급 M 근거: 모듈 2개(payment-service · notification-service), 계약 표면 무변화(테스트만 추가),
되돌림은 테스트 파일 안에서 끝난다.

코드 검증 (2026-09-28, 현재 `main`):

| 전제 | 확인 결과 |
|---|---|
| `PaymentController.confirmPayment` 가 `FAILED` 를 `PAY_005` 로 던진다 | 맞음 — `PaymentController.java` 의 `"FAILED".equals(result.status())` 분기 |
| `PaymentControllerTest` 는 FAILED 에 대해 400 만 본다 | 맞음 — `confirmPayment_failed_returns400` 에 `$.code` 단언 없음. PAY-001 도 400 이라 M12 를 못 잡는다 |
| `handlePaymentFailed` 테스트가 없다 | 맞음 — `NotificationConsumerTest` 는 `handleOrderCancelled` 만 다룬다. `NotificationConsumerIntegrationTest` 도 M13 주입에서 통과했다(D-042 §4-2) |
| reason 분기 테스트에 RESERVATION_FAILED 가 없다 | 맞음 — PAYMENT_FAILED · USER_REQUESTED · 부재 · SOMETHING_NEW 4건. M14 는 RESERVATION_FAILED 만 스킵에 더하므로 SOMETHING_NEW 테스트로는 못 잡는다 |
| RESERVATION_FAILED 가 실제 생산되는 값이다 | 맞음 — `OrderEventConsumer` 가 `OrderCancelReason.RESERVATION_FAILED` 로 발행 |
| 재사용할 fixture · 스텁 헬퍼가 있다 | 있음 — `PaymentFixture.failedPaymentDetailDto()`, `NotificationConsumerTest.stubMessage` |

범위 변화 없음. 새 테스트 클래스는 만들지 않고 기존 두 클래스에 추가한다.

## 2. 작업 항목

- [x] P1. `PaymentControllerTest#confirmPayment_failed_returns400` 에 `$.code == "PAY-005"` 단언을 더한다 (M12). 이름도 형제 `confirmPayment_unresolved_returnsPay012` 에 맞춰 `confirmPayment_failed_returnsPay005` 로 바꾼다
- [x] P2. `NotificationConsumerTest` 에 `handlePaymentFailed` 가 `userId` 로 `PAYMENT_FAILED` 알림을 만드는 테스트를 더한다 (M13). 기존 `stubMessage` 는 order.cancelled payload 형태라 payment.failed payload(`userId`·`orderId`·`amount`)용 스텁을 분리한다
- [x] P3. `NotificationConsumerTest` 에 `reason=RESERVATION_FAILED → ORDER_CANCELLED 알림 생성` 테스트를 더한다 (M14)
- [x] P4. 클래스 javadoc·`@DisplayName` 이 `handleOrderCancelled` 만 가리키므로 범위를 맞춘다 (P2 로 생긴 불일치만)

## 3. 검증 방법

D-042 §4-2 와 같은 방식으로 주입한다 — 원문을 바꾸고, 지목 테스트를 돌리고, 원문으로 되돌린다.

| id | 주입 | 기대 |
|---|---|---|
| M12 | `PaymentController` FAILED 분기를 `ErrorCode.PAY_001` 로 | P1 테스트 실패 |
| M13 | `handlePaymentFailed` 의 타입을 `NotificationType.PAYMENT_COMPLETED` 로 | P2 테스트 실패 |
| M14 | `isPaymentFailedCancel` 이 `RESERVATION_FAILED` 도 true 로 | P3 테스트 실패 |

- 각 주입마다 **해당 모듈 전체** `test` 를 돌려 실패가 새 테스트에서 나오는지 본다(다른 테스트가 우연히 잡는 것과 구분)
- 주입 전·후(원문 복원 후) 모듈 `test` 가 그린인지 확인한다
- 끝난 뒤 `git diff` 에 `src/main` 변경 0

## 미해결

- 없음. e2e 단언 A4b·A12·B3 제거는 ADR-0032 D3 가 이미 "그대로 둔다" 로 정했다
