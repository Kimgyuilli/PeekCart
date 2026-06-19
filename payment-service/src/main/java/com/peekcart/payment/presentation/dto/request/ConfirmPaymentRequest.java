package com.peekcart.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ConfirmPaymentRequest(
        @NotBlank String paymentKey,
        @NotNull Long orderId,
        @Positive long amount
) {
}
