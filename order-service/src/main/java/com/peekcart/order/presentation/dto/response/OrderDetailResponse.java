package com.peekcart.order.presentation.dto.response;

import com.peekcart.order.application.dto.OrderDetailDto;

import java.time.LocalDateTime;
import java.util.List;

public record OrderDetailResponse(
        Long id,
        String orderNumber,
        long totalAmount,
        String status,
        String receiverName,
        String phone,
        String zipcode,
        String address,
        LocalDateTime orderedAt,
        List<OrderItemResponse> items
) {

    public static OrderDetailResponse from(OrderDetailDto dto) {
        List<OrderItemResponse> items = dto.items().stream()
                .map(OrderItemResponse::from)
                .toList();
        return new OrderDetailResponse(
                dto.id(), dto.orderNumber(), dto.totalAmount(), dto.status(),
                dto.receiverName(), dto.phone(), dto.zipcode(), dto.address(),
                dto.orderedAt(), items
        );
    }
}
