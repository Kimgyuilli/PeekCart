package com.peekcart.payment.application;

import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 비동기 승인 접수. PG 호출은 재고 보호 확인 뒤 dispatcher가 수행한다 (ADR-0034). */
@Service
@RequiredArgsConstructor
public class PaymentCommandService {
    private final PaymentApprovalService approvalService;
    private final PaymentQueryService paymentQueryService;

    public PaymentDetailDto confirmPayment(Long userId, ConfirmPaymentCommand command) {
        approvalService.beginApproval(userId, command.orderId(), command.paymentKey(), command.amount());
        return paymentQueryService.getPaymentByOrderId(userId, command.orderId());
    }
}
