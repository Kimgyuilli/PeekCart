package com.peekcart.global.outbox.dto;

import java.time.LocalDateTime;

public record PaymentCompletedPayload(
        Long paymentId,
        Long orderId,
        Long userId,
        String paymentKey,
        long amount,
        String method,
        LocalDateTime approvedAt
) {
}
