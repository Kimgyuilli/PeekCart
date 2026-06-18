package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.outbox.PaymentOutboxEventPublisher;
import com.peekcart.payment.infrastructure.toss.TossConfirmResponse;
import com.peekcart.payment.infrastructure.toss.TossPaymentClient;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ServiceTest
@DisplayName("PaymentCommandService 단위 테스트")
class PaymentCommandServiceTest {

    @InjectMocks PaymentCommandService paymentCommandService;
    @Mock PaymentRepository paymentRepository;
    @Mock TossPaymentClient tossPaymentClient;
    @Mock PaymentOutboxEventPublisher outboxEventPublisher;

    @Test
    @DisplayName("confirmPayment: 성공 시 payment.requested 발행 후 APPROVED 상태와 payment.completed Outbox 이벤트가 발행된다")
    void confirmPayment_success() {
        Payment payment = PaymentFixture.readyPaymentWithId();
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        TossConfirmResponse response = new TossConfirmResponse(
                PaymentFixture.DEFAULT_PAYMENT_KEY, command.orderId().toString(),
                "DONE", "카드", "2026-03-25T14:00:00+09:00");

        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));
        given(tossPaymentClient.confirm(command.paymentKey(), command.orderId().toString(), command.amount()))
                .willReturn(response);

        PaymentDetailDto result = paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command);

        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.method()).isEqualTo("카드");
        then(outboxEventPublisher).should().publishPaymentRequested(any(Payment.class), eq(PaymentFixture.DEFAULT_USER_ID));
        then(outboxEventPublisher).should().publishPaymentCompleted(any(Payment.class), eq(PaymentFixture.DEFAULT_USER_ID));
    }

    @Test
    @DisplayName("confirmPayment: Toss API 실패 시 FAILED 상태와 payment.failed Outbox 이벤트가 발행된다")
    void confirmPayment_tossFailure_failsPayment() {
        Payment payment = PaymentFixture.readyPaymentWithId();
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();

        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));
        given(tossPaymentClient.confirm(any(), any(), eq(command.amount())))
                .willThrow(new RuntimeException("Toss API error"));

        PaymentDetailDto result = paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command);

        assertThat(result.status()).isEqualTo("FAILED");
        then(outboxEventPublisher).should().publishPaymentRequested(any(Payment.class), eq(PaymentFixture.DEFAULT_USER_ID));
        then(outboxEventPublisher).should().publishPaymentFailed(any(Payment.class), eq(PaymentFixture.DEFAULT_USER_ID));
    }

    @Test
    @DisplayName("confirmPayment: 결제 정보가 없으면 PAY-003 예외가 발생한다")
    void confirmPayment_notFound_throwsPAY003() {
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_003);
    }

    @Test
    @DisplayName("confirmPayment: 금액 불일치 시 PAY-001 예외가 발생한다")
    void confirmPayment_amountMismatch_throwsPAY001() {
        Payment payment = PaymentFixture.readyPaymentWithId();
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_ORDER_ID, 99_999L);

        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_001);
    }

    @Test
    @DisplayName("confirmPayment: 본인 결제가 아니면 PAY-007 로 차단되고 Toss 미호출")
    void confirmPayment_notOwner_throwsPAY007() {
        Payment payment = PaymentFixture.readyPaymentWithId();   // userId = DEFAULT_USER_ID(1L)
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(2L, command))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_007);
        then(tossPaymentClient).should(never()).confirm(any(), any(), any(Long.class));
        then(outboxEventPublisher).should(never()).publishPaymentRequested(any(), any());
    }

    @Test
    @DisplayName("confirmPayment: 예약 미확정(reserve→pay 게이트)이면 PAY-008 로 Toss 호출 전 차단되고 payment.requested 미발행")
    void confirmPayment_reservationNotConfirmed_throwsPAY008() {
        Payment payment = PaymentFixture.pendingPaymentWithId();   // readyForPayment = false
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_008);
        then(outboxEventPublisher).should(never()).publishPaymentRequested(any(), any());
        then(tossPaymentClient).should(never()).confirm(any(), any(), any(Long.class));
        then(outboxEventPublisher).should(never()).publishPaymentCompleted(any(), any());
    }

    @Test
    @DisplayName("confirmPayment: 주문 취소로 종료된 결제(취소 게이트)면 PAY-009 로 차단된다")
    void confirmPayment_cancelled_throwsPAY009() {
        Payment payment = PaymentFixture.readyPaymentWithId();
        payment.cancelBeforePayment();   // order.cancelled 선수신 → CANCELLED
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_009);
        then(tossPaymentClient).should(never()).confirm(any(), any(), any(Long.class));
    }
}
