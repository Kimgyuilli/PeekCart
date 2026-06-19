package com.peekcart.order.application.dto;

import com.peekcart.order.domain.model.Order;

import java.time.LocalDateTime;

/**
 * 주문 목록 조회용 DTO. orderItems에 접근하지 않아 N+1 쿼리를 방지한다.
 */
public record OrderSummaryDto(
        Long id,
        String orderNumber,
        long totalAmount,
        String status,
        LocalDateTime orderedAt
) {

    public static OrderSummaryDto from(Order order) {
        return new OrderSummaryDto(
                order.getId(),
                order.getOrderNumber(),
                order.getTotalAmount(),
                order.getStatus().name(),
                order.getOrderedAt()
        );
    }
}
