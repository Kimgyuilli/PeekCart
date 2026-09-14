package com.peekcart.payment.application;

import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.infrastructure.toss.ApprovalExecutor;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

/**
 * {@link PaymentCommandService} 는 이제 <b>오케스트레이션만</b> 한다 (ADR-0023 D1).
 * 게이트 검증·원장 전이는 {@link PaymentApprovalServiceTest} 소관이므로, 여기서는
 * <b>T1 → PG → T2 의 순서와 generation 전달</b>이라는 이 클래스 고유의 계약만 본다.
 */
@ServiceTest
@DisplayName("PaymentCommandService 단위 테스트 — T1/PG/T2 오케스트레이션")
class PaymentCommandServiceTest {

    PaymentCommandService paymentCommandService;
    @Mock PaymentApprovalService approvalService;
    @Mock ApprovalExecutor approvalExecutor;
    @Mock PaymentQueryService paymentQueryService;

    @BeforeEach
    void setUp() {
        paymentCommandService = new PaymentCommandService(approvalService, approvalExecutor, paymentQueryService);
    }

    @Test
    @DisplayName("PG 호출이 T1 커밋 뒤, T2 확정 앞에서 일어난다 — 이 순서가 D-020 수정의 본체다")
    void confirmPayment_callsPgBetweenTwoCommits() {
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        PaymentApproval approval = PaymentFixture.claimedApproval();
        ApprovalOutcome outcome = ApprovalOutcome.succeeded("카드", LocalDateTime.now(), "{}");

        given(approvalService.beginApproval(
                PaymentFixture.DEFAULT_USER_ID, command.orderId(), command.paymentKey(), command.amount()))
                .willReturn(approval);
        given(approvalExecutor.execute(approval)).willReturn(new ApprovalExecutor.CallResult(outcome, 1));
        given(approvalService.finalizeApproval(eq(command.orderId()), anyLong(), any(), anyInt()))
                .willReturn(PaymentStatus.APPROVED);
        given(paymentQueryService.getPaymentByOrderId(PaymentFixture.DEFAULT_USER_ID, command.orderId()))
                .willReturn(PaymentFixture.approvedPaymentDetailDto());

        PaymentDetailDto result = paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command);

        assertThat(result.status()).isEqualTo("APPROVED");
        InOrder order = inOrder(approvalService, approvalExecutor);
        order.verify(approvalService).beginApproval(anyLong(), anyLong(), any(), anyLong());
        order.verify(approvalExecutor).execute(approval);
        order.verify(approvalService).finalizeApproval(eq(command.orderId()), anyLong(), any(), anyInt());
    }

    @Test
    @DisplayName("확정에 claim 당시 generation 을 그대로 넘긴다 — 소유권이 넘어갔다면 T2 가 무시돼야 한다")
    void confirmPayment_passesClaimGeneration() {
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        PaymentApproval approval = PaymentFixture.approval(
                com.peekcart.payment.domain.model.ApprovalStatus.CLAIMED, 7L, LocalDateTime.now());
        ApprovalOutcome outcome = ApprovalOutcome.failed("INVALID_CARD", "{}");

        given(approvalService.beginApproval(anyLong(), anyLong(), any(), anyLong())).willReturn(approval);
        given(approvalExecutor.execute(approval)).willReturn(new ApprovalExecutor.CallResult(outcome, 1));
        given(approvalService.finalizeApproval(anyLong(), anyLong(), any(), anyInt()))
                .willReturn(PaymentStatus.FAILED);
        given(paymentQueryService.getPaymentByOrderId(anyLong(), anyLong()))
                .willReturn(PaymentFixture.failedPaymentDetailDto());

        paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command);

        org.mockito.BDDMockito.then(approvalService).should()
                .finalizeApproval(command.orderId(), 7L, outcome, 1);
    }

    @Test
    @DisplayName("결과 불명이면 PENDING 을 그대로 돌려준다 — 실패로 단언하지 않는다(ADR-0023 D8)")
    void confirmPayment_unresolved_returnsPending() {
        ConfirmPaymentCommand command = PaymentFixture.confirmPaymentCommand();
        PaymentApproval approval = PaymentFixture.claimedApproval();

        given(approvalService.beginApproval(anyLong(), anyLong(), any(), anyLong())).willReturn(approval);
        given(approvalExecutor.execute(approval)).willReturn(
                new ApprovalExecutor.CallResult(ApprovalOutcome.unresolved("timeout"), 1));
        given(approvalService.finalizeApproval(anyLong(), anyLong(), any(), anyInt()))
                .willReturn(PaymentStatus.PENDING);
        given(paymentQueryService.getPaymentByOrderId(anyLong(), anyLong()))
                .willReturn(PaymentFixture.pendingPaymentDetailDto());

        PaymentDetailDto result = paymentCommandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID, command);

        assertThat(result.status()).isEqualTo("PENDING");
    }
}
