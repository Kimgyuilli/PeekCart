package com.peekcart.payment.infrastructure.toss;

/**
 * Toss Payments 결제 승인 응답 DTO.
 */
public record TossConfirmResponse(
        String paymentKey,
        String orderId,
        String status,
        String method,
        String approvedAt
) {
}
