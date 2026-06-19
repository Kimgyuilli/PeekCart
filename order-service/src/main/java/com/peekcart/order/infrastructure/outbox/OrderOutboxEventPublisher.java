package com.peekcart.order.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.MdcSnapshot;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.global.outbox.dto.OrderCancelledPayload;
import com.peekcart.global.outbox.dto.OrderCreatedPayload;
import com.peekcart.global.outbox.dto.OrderItemPayload;
import com.peekcart.order.domain.model.Order;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class OrderOutboxEventPublisher {

    private static final String AGGREGATE_TYPE = "ORDER";
    private static final String ORDER_CREATED = "order.created";
    private static final String ORDER_CANCELLED = "order.cancelled";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publishOrderCreated(Order order) {
        List<OrderItemPayload> items = order.getOrderItems().stream()
                .map(item -> new OrderItemPayload(
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice()))
                .toList();

        OrderCreatedPayload payload = new OrderCreatedPayload(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                order.getTotalAmount(),
                items,
                order.getReceiverName(),
                order.getAddress());

        saveOutboxEvent(ORDER_CREATED, order.getId().toString(), payload);
    }

    public void publishOrderCancelled(Order order) {
        OrderCancelledPayload payload = new OrderCancelledPayload(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId());

        saveOutboxEvent(ORDER_CANCELLED, order.getId().toString(), payload);
    }

    private void saveOutboxEvent(String eventType, String aggregateId, Object payload) {
        MdcSnapshot.Snapshot mdc = MdcSnapshot.current();
        OutboxEvent outboxEvent = OutboxEvent.create(AGGREGATE_TYPE, aggregateId, eventType,
                mdc.traceId(), mdc.userId(),
                eventId -> serialize(new KafkaEventEnvelope(eventId, eventType, LocalDateTime.now(), payload)));
        outboxEventRepository.save(outboxEvent);
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox 이벤트 직렬화 실패", e);
        }
    }
}
