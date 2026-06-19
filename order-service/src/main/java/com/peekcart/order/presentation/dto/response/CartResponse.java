package com.peekcart.order.presentation.dto.response;

import com.peekcart.order.application.dto.CartDetailDto;

import java.util.List;

public record CartResponse(Long id, List<CartItemResponse> items) {

    public static CartResponse from(CartDetailDto dto) {
        List<CartItemResponse> items = dto.items().stream()
                .map(CartItemResponse::from)
                .toList();
        return new CartResponse(dto.id(), items);
    }
}
