package com.peekcart.order.presentation.dto.response;

import com.peekcart.order.application.dto.OrderItemDto;

public record OrderItemResponse(Long id, Long productId, int quantity, long unitPrice) {

    public static OrderItemResponse from(OrderItemDto dto) {
        return new OrderItemResponse(dto.id(), dto.productId(), dto.quantity(), dto.unitPrice());
    }
}
