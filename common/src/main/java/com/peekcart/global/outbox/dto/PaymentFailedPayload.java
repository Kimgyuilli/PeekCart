package com.peekcart.global.outbox.dto;

public record PaymentFailedPayload(
        Long paymentId,
        Long orderId,
        Long userId,
        String paymentKey,
        long amount
) {
}
