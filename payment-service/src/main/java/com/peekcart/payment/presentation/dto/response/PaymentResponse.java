package com.peekcart.payment.presentation.dto.response;

import com.peekcart.payment.application.dto.PaymentDetailDto;

import java.time.LocalDateTime;

public record PaymentResponse(
        Long id,
        Long orderId,
        String paymentKey,
        long amount,
        String status,
        String method,
        LocalDateTime approvedAt,
        LocalDateTime createdAt
) {

    public static PaymentResponse from(PaymentDetailDto dto) {
        return new PaymentResponse(
                dto.id(),
                dto.orderId(),
                dto.paymentKey(),
                dto.amount(),
                dto.status(),
                dto.method(),
                dto.approvedAt(),
                dto.createdAt()
        );
    }
}
