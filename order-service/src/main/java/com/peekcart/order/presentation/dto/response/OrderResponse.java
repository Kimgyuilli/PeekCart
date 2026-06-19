package com.peekcart.order.presentation.dto.response;

import com.peekcart.order.application.dto.OrderSummaryDto;

import java.time.LocalDateTime;

public record OrderResponse(Long id, String orderNumber, long totalAmount, String status, LocalDateTime orderedAt) {

    public static OrderResponse from(OrderSummaryDto dto) {
        return new OrderResponse(dto.id(), dto.orderNumber(), dto.totalAmount(), dto.status(), dto.orderedAt());
    }
}
