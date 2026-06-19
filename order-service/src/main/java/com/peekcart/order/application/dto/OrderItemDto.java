package com.peekcart.order.application.dto;

import com.peekcart.order.domain.model.OrderItem;

public record OrderItemDto(Long id, Long productId, int quantity, long unitPrice) {

    public static OrderItemDto from(OrderItem item) {
        return new OrderItemDto(item.getId(), item.getProductId(), item.getQuantity(), item.getUnitPrice());
    }
}
