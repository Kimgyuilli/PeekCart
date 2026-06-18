package com.peekcart.global.outbox.dto;

/**
 * 결제 요청 이벤트({@code payment.requested}) payload (ADR-0012 §D4 refine).
 * Payment 가 발행하고 Order 가 소비해 주문을 PAYMENT_REQUESTED 로 전이한다 (동기 OrderPort.transitionToPaymentRequested 대체).
 * 파티션 키 = {@code orderId}.
 *
 * @param orderId 주문 PK (파티션 키)
 * @param userId  주문 소유자
 */
public record PaymentRequestedPayload(
        Long orderId,
        Long userId
) {
}
