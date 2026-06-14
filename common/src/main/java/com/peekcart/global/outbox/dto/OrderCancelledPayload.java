package com.peekcart.global.outbox.dto;

public record OrderCancelledPayload(
        Long orderId,
        String orderNumber,
        Long userId
) {
}
