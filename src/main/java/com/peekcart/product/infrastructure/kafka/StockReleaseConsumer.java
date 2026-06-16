package com.peekcart.product.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.product.application.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code order.cancelled} / {@code payment.failed} 를 소비하여 예약 재고를 복구하는 Consumer (ADR-0012 D3).
 * 복구 권한은 예약 원장의 {@code RESERVED → RELEASED} 원자 CAS 로만 부여된다(double-release 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReleaseConsumer {

    private static final String GROUP_ORDER_CANCELLED = "product-svc-order-cancelled-group";
    private static final String GROUP_PAYMENT_FAILED = "product-svc-payment-failed-group";

    private final StockReservationService reservationService;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    @KafkaListener(topics = "order.cancelled", groupId = GROUP_ORDER_CANCELLED)
    @Transactional
    public void handleOrderCancelled(String message) {
        release(message, GROUP_ORDER_CANCELLED);
    }

    @KafkaListener(topics = "payment.failed", groupId = GROUP_PAYMENT_FAILED)
    @Transactional
    public void handlePaymentFailed(String message) {
        release(message, GROUP_PAYMENT_FAILED);
    }

    private void release(String message, String consumerGroup) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, consumerGroup, () -> {
            Long orderId = payload.get("orderId").asLong();
            reservationService.release(orderId);
        });
    }
}
