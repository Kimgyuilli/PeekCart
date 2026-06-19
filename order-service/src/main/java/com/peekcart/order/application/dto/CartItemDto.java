package com.peekcart.order.application.dto;

import com.peekcart.order.domain.model.CartItem;

public record CartItemDto(Long id, Long productId, int quantity) {

    public static CartItemDto from(CartItem item) {
        return new CartItemDto(item.getId(), item.getProductId(), item.getQuantity());
    }
}
