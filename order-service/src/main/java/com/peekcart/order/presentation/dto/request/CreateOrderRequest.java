package com.peekcart.order.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

public record CreateOrderRequest(
        @NotBlank String receiverName,
        @NotBlank String phone,
        @NotBlank String zipcode,
        @NotBlank String address
) {
}
