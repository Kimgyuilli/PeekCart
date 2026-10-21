package com.peekcart.payment.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Pattern;

public record ConfirmPaymentRequest(
        @NotBlank String paymentKey,
        @NotNull Long orderId,
        @Positive long amount,
        @Pattern(regexp = "[A-Za-z0-9_-]{6,64}") String pgOrderId
) {
}
