package com.peekcart.payment.application.dto;

import com.peekcart.payment.domain.model.Payment;

import java.time.LocalDateTime;

public record PaymentDetailDto(
        Long id,
        Long orderId,
        String paymentKey,
        long amount,
        String status,
        String method,
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {

    public static PaymentDetailDto from(Payment payment) {
        return new PaymentDetailDto(
                payment.getId(),
                payment.getOrderId(),
                payment.getPaymentKey(),
                payment.getAmount(),
                payment.getStatus().name(),
                payment.getMethod(),
                payment.getApprovedAt(),
                payment.getCreatedAt()
        );
    }
}
