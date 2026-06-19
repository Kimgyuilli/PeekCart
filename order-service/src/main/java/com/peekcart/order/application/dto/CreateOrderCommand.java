package com.peekcart.order.application.dto;

public record CreateOrderCommand(
        String receiverName,
        String phone,
        String zipcode,
        String address
) {
}
