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
 * {@code payment.completed} 를 소비하여 예약을 확정(commit)하는 Consumer (ADR-0012 ④, strangler-3).
 * 확정 권한은 예약 원장의 {@code RESERVED → CONFIRMED} 원자 CAS 로 부여되며, commit-실패(결제됐으나
 * 재고 미확정) 는 보상 경로로 수렴한다. release(복구) 와 의미를 분리하기 위해 별도 consumer 로 둔다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockConfirmConsumer {

    private static final String GROUP_PAYMENT_COMPLETED = "product-svc-payment-completed-group";

    private final StockReservationService reservationService;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    @KafkaListener(topics = "payment.completed", groupId = GROUP_PAYMENT_COMPLETED)
    @Transactional
    public void handlePaymentCompleted(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_COMPLETED, () -> {
            Long orderId = payload.get("orderId").asLong();
            reservationService.confirm(orderId);
        });
    }
}
