package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.application.port.OrderPort;
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
import static org.mockito.Mockito.doThrow;

@ServiceTest
@DisplayName("PaymentCommandService 단위 테스트")
class PaymentCommandServiceTest {

    @InjectMocks PaymentCommandService paymentCommandService;
    @Mock PaymentRepository paymentRepository;
    @Mock TossPaymentClient tossPaymentClient;
    @Mock OrderPort orderPort;
    @Mock PaymentOutboxEventPublisher outboxEventPublisher;

    @Test
    @DisplayName("confirmPayment: 성공 시 APPROVED 상태와 payment.completed Outbox 이벤트가 발행된다")
    void confirmPayment_success() {
        Payment payment = PaymentFixture.pendingPaymentWithId();
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
        then(orderPort).should().verifyOrderOwner(PaymentFixture.DEFAULT_USER_ID, command.orderId());
        then(orderPort).should().transitionToPaymentRequested(command.orderId());
        then(outboxEventPublisher).should().publishPaymentCompleted(any(Payment.class), eq(PaymentFixture.DEFAULT_USER_ID));
    }

    @Test
    @DisplayName("confirmPayment: Toss API 실패 시 FAILED 상태와 payment.failed Outbox 이벤트가 발행된다")
    void confirmPayment_tossFailure_failsPayment() {
        Payment payment = PaymentFixture.pendingPaymentWithId();
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();

        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));
        given(tossPaymentClient.confirm(any(), any(), eq(command.amount())))
                .willThrow(new RuntimeException("Toss API error"));

        PaymentDetailDto result = paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command);

        assertThat(result.status()).isEqualTo("FAILED");
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
        Payment payment = PaymentFixture.pendingPaymentWithId();
        ConfirmPaymentCommand command = new ConfirmPaymentCommand(
                PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_ORDER_ID, 99_999L);

        given(paymentRepository.findByOrderId(command.orderId())).willReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_001);
    }

    @Test
    @DisplayName("confirmPayment: 주문 소유권 검증에 실패하면 예외가 전파된다")
    void confirmPayment_notOwner_throwsException() {
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        doThrow(new RuntimeException("Not owner")).when(orderPort)
                .verifyOrderOwner(2L, command.orderId());

        assertThatThrownBy(() -> paymentCommandService.confirmPayment(2L, command))
                .isInstanceOf(RuntimeException.class);
    }
}
