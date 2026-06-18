package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ServiceTest
@DisplayName("PaymentQueryService 단위 테스트")
class PaymentQueryServiceTest {

    @InjectMocks PaymentQueryService paymentQueryService;
    @Mock PaymentRepository paymentRepository;

    @Test
    @DisplayName("getPaymentByOrderId: 결제 정보를 정상 조회한다")
    void getPaymentByOrderId_success() {
        Payment payment = PaymentFixture.pendingPaymentWithId();
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID))
                .willReturn(Optional.of(payment));

        PaymentDetailDto result = paymentQueryService.getPaymentByOrderId(
                PaymentFixture.DEFAULT_USER_ID, PaymentFixture.DEFAULT_ORDER_ID);

        assertThat(result.orderId()).isEqualTo(PaymentFixture.DEFAULT_ORDER_ID);
        assertThat(result.amount()).isEqualTo(PaymentFixture.DEFAULT_AMOUNT);
    }

    @Test
    @DisplayName("getPaymentByOrderId: 결제 정보가 없으면 PAY-003 예외가 발생한다")
    void getPaymentByOrderId_notFound_throwsPAY003() {
        given(paymentRepository.findByOrderId(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> paymentQueryService.getPaymentByOrderId(1L, 99L))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_003);
    }

    @Test
    @DisplayName("getPaymentByOrderId: 본인 주문의 결제가 아니면 PAY-007 예외가 발생한다")
    void getPaymentByOrderId_notOwner_throwsPAY007() {
        Payment payment = PaymentFixture.pendingPaymentWithId();   // userId = DEFAULT_USER_ID(1L)
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID))
                .willReturn(Optional.of(payment));

        assertThatThrownBy(() -> paymentQueryService.getPaymentByOrderId(2L, PaymentFixture.DEFAULT_ORDER_ID))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_007);
    }
}
