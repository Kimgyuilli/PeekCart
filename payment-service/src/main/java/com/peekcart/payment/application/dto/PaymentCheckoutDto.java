package com.peekcart.payment.application.dto;

/** PG 인증 전에 받아 같은 pgOrderId와 amount를 confirm에 돌려준다. */
public record PaymentCheckoutDto(Long orderId, String pgOrderId, long amount) {
}
