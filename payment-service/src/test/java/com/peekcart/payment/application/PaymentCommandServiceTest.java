package com.peekcart.payment.application;

import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ServiceTest
class PaymentCommandServiceTest {
    @Mock PaymentApprovalService approvalService;
    @Mock PaymentQueryService queryService;

    @Test
    void acceptsWithoutFinalizingApproval() {
        var command = PaymentFixture.confirmPaymentCommand();
        given(queryService.getPaymentByOrderId(PaymentFixture.DEFAULT_USER_ID, command.orderId()))
                .willReturn(PaymentFixture.pendingPaymentDetailDto());
        var result = new PaymentCommandService(approvalService, queryService)
                .confirmPayment(PaymentFixture.DEFAULT_USER_ID, command);
        assertThat(result.status()).isEqualTo("PENDING");
        verify(approvalService).beginApproval(PaymentFixture.DEFAULT_USER_ID, command.orderId(),
                command.paymentKey(), command.amount());
        verifyNoMoreInteractions(approvalService);
    }
}
