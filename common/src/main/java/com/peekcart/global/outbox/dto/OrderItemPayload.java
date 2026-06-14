package com.peekcart.global.outbox.dto;

public record OrderItemPayload(
        Long productId,
        int quantity,
        long unitPrice
) {
}
