package com.peekcart.order.application.dto;

import com.peekcart.order.domain.model.Cart;

import java.util.List;

public record CartDetailDto(Long id, List<CartItemDto> items) {

    public static CartDetailDto from(Cart cart) {
        List<CartItemDto> items = cart.getItems().stream()
                .map(CartItemDto::from)
                .toList();
        return new CartDetailDto(cart.getId(), items);
    }

    public static CartDetailDto empty() {
        return new CartDetailDto(null, List.of());
    }
}
