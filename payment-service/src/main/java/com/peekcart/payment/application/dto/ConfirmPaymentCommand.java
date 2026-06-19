package com.peekcart.payment.application.dto;

public record ConfirmPaymentCommand(
        String paymentKey,
        Long orderId,
        long amount
) {
}
