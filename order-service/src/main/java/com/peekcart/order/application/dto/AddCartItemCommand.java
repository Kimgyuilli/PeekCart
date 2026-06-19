package com.peekcart.order.application.dto;

public record AddCartItemCommand(Long productId, int quantity) {
}
