package com.peekcart.payment.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Payment 도메인 단위 테스트")
class PaymentTest {

    @Nested
    @DisplayName("create")
    class Create {

        @Test
        @DisplayName("초기 상태가 PENDING이다")
        void initialStatusIsPending() {
            Payment payment = PaymentFixture.pendingPayment();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        @Test
        @DisplayName("orderId와 amount가 설정된다")
        void setsOrderIdAndAmount() {
            Payment payment = PaymentFixture.pendingPayment();
            assertThat(payment.getOrderId()).isEqualTo(PaymentFixture.DEFAULT_ORDER_ID);
            assertThat(payment.getAmount()).isEqualTo(PaymentFixture.DEFAULT_AMOUNT);
        }

        @Test
        @DisplayName("UUID 형식의 임시 paymentKey가 생성된다")
        void generatesUuidPaymentKey() {
            Payment payment = PaymentFixture.pendingPayment();
            assertThat(payment.getPaymentKey()).isNotBlank();
        }

        @Test
        @DisplayName("createdAt이 설정된다")
        void setsCreatedAt() {
            Payment payment = PaymentFixture.pendingPayment();
            assertThat(payment.getCreatedAt()).isNotNull();
        }
    }

    @Nested
    @DisplayName("assignPaymentKey")
    class AssignPaymentKey {

        @Test
        @DisplayName("PENDING 상태에서 paymentKey를 교체한다")
        void fromPending_success() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.assignPaymentKey("new-key");
            assertThat(payment.getPaymentKey()).isEqualTo("new-key");
        }

        @Test
        @DisplayName("APPROVED 상태에서 호출하면 PAY-004 예외가 발생한다")
        void fromApproved_throwsPAY004() {
            Payment payment = PaymentFixture.approvedPayment();

            assertThatThrownBy(() -> payment.assignPaymentKey("new-key"))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }

        @Test
        @DisplayName("FAILED 상태에서 호출하면 PAY-004 예외가 발생한다")
        void fromFailed_throwsPAY004() {
            Payment payment = PaymentFixture.failedPayment();

            assertThatThrownBy(() -> payment.assignPaymentKey("new-key"))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }
    }

    @Nested
    @DisplayName("approve")
    class Approve {

        @Test
        @DisplayName("PENDING 상태에서 승인하면 APPROVED가 된다")
        void fromPending_success() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.approve("카드", LocalDateTime.now());
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        }

        @Test
        @DisplayName("승인 시 method와 approvedAt이 설정된다")
        void setsMethodAndApprovedAt() {
            Payment payment = PaymentFixture.pendingPayment();
            LocalDateTime approvedAt = LocalDateTime.of(2026, 3, 25, 14, 0);
            payment.approve("카드", approvedAt);

            assertThat(payment.getMethod()).isEqualTo("카드");
            assertThat(payment.getApprovedAt()).isEqualTo(approvedAt);
        }

        @Test
        @DisplayName("APPROVED 상태에서 다시 승인하면 PAY-004 예외가 발생한다")
        void fromApproved_throwsPAY004() {
            Payment payment = PaymentFixture.approvedPayment();

            assertThatThrownBy(() -> payment.approve("카드", LocalDateTime.now()))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }

        @Test
        @DisplayName("FAILED 상태에서 승인하면 PAY-004 예외가 발생한다")
        void fromFailed_throwsPAY004() {
            Payment payment = PaymentFixture.failedPayment();

            assertThatThrownBy(() -> payment.approve("카드", LocalDateTime.now()))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }
    }

    @Nested
    @DisplayName("fail")
    class Fail {

        @Test
        @DisplayName("PENDING 상태에서 실패하면 FAILED가 된다")
        void fromPending_success() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.fail();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        }

        @Test
        @DisplayName("APPROVED 상태에서 실패하면 PAY-004 예외가 발생한다")
        void fromApproved_throwsPAY004() {
            Payment payment = PaymentFixture.approvedPayment();

            assertThatThrownBy(payment::fail)
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }

        @Test
        @DisplayName("FAILED 상태에서 다시 실패하면 PAY-004 예외가 발생한다")
        void fromFailed_throwsPAY004() {
            Payment payment = PaymentFixture.failedPayment();

            assertThatThrownBy(payment::fail)
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }
    }

    @Nested
    @DisplayName("validateAmount")
    class ValidateAmount {

        @Test
        @DisplayName("금액이 일치하면 예외가 발생하지 않는다")
        void matchingAmount_noException() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.validateAmount(PaymentFixture.DEFAULT_AMOUNT);
        }

        @Test
        @DisplayName("금액이 불일치하면 PAY-001 예외가 발생한다")
        void mismatchAmount_throwsPAY001() {
            Payment payment = PaymentFixture.pendingPayment();

            assertThatThrownBy(() -> payment.validateAmount(99_999L))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_001);
        }
    }

    @Nested
    @DisplayName("verifyOwner (Seam 1 — payment-로컬 소유자 검증)")
    class VerifyOwner {

        @Test
        @DisplayName("소유자가 일치하면 예외가 발생하지 않는다")
        void matchingOwner_noException() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.verifyOwner(PaymentFixture.DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("소유자가 불일치하면 PAY-007 예외가 발생한다")
        void mismatchOwner_throwsPAY007() {
            Payment payment = PaymentFixture.pendingPayment();

            assertThatThrownBy(() -> payment.verifyOwner(2L))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_007);
        }
    }

    @Nested
    @DisplayName("ensureConfirmable (reserve→pay + 취소 게이트)")
    class EnsureConfirmable {

        @Test
        @DisplayName("예약 확정(ready)된 PENDING 이면 통과한다")
        void readyPending_passes() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.markReadyForPayment();
            payment.ensureConfirmable();
        }

        @Test
        @DisplayName("예약 미확정이면 PAY-008 예외가 발생한다")
        void notReady_throwsPAY008() {
            Payment payment = PaymentFixture.pendingPayment();

            assertThatThrownBy(payment::ensureConfirmable)
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_008);
        }

        @Test
        @DisplayName("취소(CANCELLED)된 결제면 PAY-009 예외가 발생한다")
        void cancelled_throwsPAY009() {
            Payment payment = PaymentFixture.pendingPayment();
            payment.markReadyForPayment();
            payment.cancelBeforePayment();

            assertThatThrownBy(payment::ensureConfirmable)
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_009);
        }
    }

    @Nested
    @DisplayName("cancelBeforePayment (취소 게이트 + 상태머신 닫기)")
    class CancelBeforePayment {

        @Test
        @DisplayName("PENDING 이면 CANCELLED 로 종료하고 보상 불필요(false)")
        void fromPending_cancels() {
            Payment payment = PaymentFixture.pendingPayment();

            boolean compensationNeeded = payment.cancelBeforePayment();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
            assertThat(compensationNeeded).isFalse();
        }

        @Test
        @DisplayName("APPROVED 면 덮어쓰지 않고 보상 필요(true) 를 반환한다 (과금-후-취소)")
        void fromApproved_noOverwrite_needsCompensation() {
            Payment payment = PaymentFixture.approvedPayment();

            boolean compensationNeeded = payment.cancelBeforePayment();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
            assertThat(compensationNeeded).isTrue();
        }

        @Test
        @DisplayName("FAILED 면 no-op (덮어쓰지 않고 보상도 불필요)")
        void fromFailed_noop() {
            Payment payment = PaymentFixture.failedPayment();

            boolean compensationNeeded = payment.cancelBeforePayment();

            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
            assertThat(compensationNeeded).isFalse();
        }
    }
}
