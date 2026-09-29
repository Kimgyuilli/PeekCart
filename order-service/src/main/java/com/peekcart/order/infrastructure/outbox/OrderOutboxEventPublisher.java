package com.peekcart.order.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.MdcSnapshot;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.CompensationReason;
import com.peekcart.global.outbox.dto.CompensationRequestedPayload;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.global.outbox.dto.OrderCancelReason;
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
    private static final String ORDER_COMPENSATION_REQUESTED = "order.compensation.requested";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    public void publishOrderCreated(Order order) {
        List<OrderItemPayload> items = toItemPayloads(order);

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

    /**
     * 주문 취소를 발행한다.
     *
     * @param reason 취소 사유. 진입점이 명시적으로 전달한다 — 소비자가 사유를 추론하지 않게 하기 위함이다
     */
    public void publishOrderCancelled(Order order, OrderCancelReason reason) {
        OrderCancelledPayload payload = new OrderCancelledPayload(
                order.getId(),
                order.getOrderNumber(),
                order.getUserId(),
                toItemPayloads(order),
                reason);

        saveOutboxEvent(ORDER_CANCELLED, order.getId().toString(), payload);
    }

    /**
     * 환불 보상을 요청한다 (ADR-0018 D1). <b>보상 원장 기록과 같은 트랜잭션</b>에서 호출돼야 한다 —
     * 부분 커밋은 "감지했는데 요청이 없는" 영구 미결을 만든다.
     *
     * <p>aggregateType/aggregateId 는 {@code ORDER}/{@code orderId} 로 고정한다. backfill SQL 의
     * {@code NOT EXISTS} 조건이 같은 키를 쓴다(멱등 근거는 producer DB 안에서만 성립한다).
     *
     * @param detectedAt 감지 시각 (발행 시각이 아니다)
     */
    public void publishCompensationRequested(Long orderId, CompensationReason reason, LocalDateTime detectedAt) {
        saveOutboxEvent(ORDER_COMPENSATION_REQUESTED, orderId.toString(),
                new CompensationRequestedPayload(orderId, reason, detectedAt));
    }

    private List<OrderItemPayload> toItemPayloads(Order order) {
        return order.getOrderItems().stream()
                .map(item -> new OrderItemPayload(
                        item.getProductId(),
                        item.getQuantity(),
                        item.getUnitPrice()))
                .toList();
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
