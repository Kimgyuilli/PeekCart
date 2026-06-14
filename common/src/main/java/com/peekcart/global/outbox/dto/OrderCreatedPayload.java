package com.peekcart.global.outbox.dto;

import java.util.List;

public record OrderCreatedPayload(
        Long orderId,
        String orderNumber,
        Long userId,
        long totalAmount,
        List<OrderItemPayload> items,
        String receiverName,
        String address
) {
}
