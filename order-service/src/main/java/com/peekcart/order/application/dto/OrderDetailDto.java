package com.peekcart.order.application.dto;

import com.peekcart.order.domain.model.Order;

import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailDto(
        Long id,
        String orderNumber,
        long totalAmount,
        String status,
        String receiverName,
        String phone,
        String zipcode,
        String address,
        LocalDateTime orderedAt,
        List<OrderItemDto> items
) {

    public static OrderDetailDto from(Order order) {
        List<OrderItemDto> items = order.getOrderItems().stream()
                .map(OrderItemDto::from)
                .toList();
        return new OrderDetailDto(
                order.getId(),
                order.getOrderNumber(),
                order.getTotalAmount(),
                order.getStatus().name(),
                order.getReceiverName(),
                order.getPhone(),
                order.getZipcode(),
                order.getAddress(),
                order.getOrderedAt(),
                items
        );
    }
}
