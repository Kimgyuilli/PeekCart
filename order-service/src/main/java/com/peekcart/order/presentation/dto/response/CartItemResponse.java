package com.peekcart.order.presentation.dto.response;

import com.peekcart.order.application.dto.CartItemDto;

public record CartItemResponse(Long id, Long productId, int quantity) {

    public static CartItemResponse from(CartItemDto dto) {
        return new CartItemResponse(dto.id(), dto.productId(), dto.quantity());
    }
}
