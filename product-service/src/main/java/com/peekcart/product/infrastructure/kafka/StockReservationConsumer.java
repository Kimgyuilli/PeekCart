package com.peekcart.product.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.product.application.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code order.created} 를 소비하여 재고를 예약하는 Consumer (ADR-0012 D3).
 * 멱등 + 차감 + 원장 + 결과 발행을 단일 트랜잭션으로 처리한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StockReservationConsumer {

    private static final String GROUP_ORDER_CREATED = "product-svc-order-created-group";

    private final StockReservationService reservationService;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    @KafkaListener(topics = "order.created", groupId = GROUP_ORDER_CREATED)
    @Transactional
    public void handleOrderCreated(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_ORDER_CREATED, () -> {
            Long orderId = payload.get("orderId").asLong();
            List<ReservedItemPayload> items = parseItems(payload.get("items"));
            reservationService.reserve(orderId, eventId, items);
        });
    }

    private List<ReservedItemPayload> parseItems(JsonNode itemsNode) {
        List<ReservedItemPayload> items = new ArrayList<>();
        if (itemsNode != null && itemsNode.isArray()) {
            for (JsonNode item : itemsNode) {
                items.add(new ReservedItemPayload(
                        item.get("productId").asLong(),
                        item.get("quantity").asInt()));
            }
        }
        return items;
    }
}
