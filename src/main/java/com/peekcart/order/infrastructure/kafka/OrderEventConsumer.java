package com.peekcart.order.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.order.infrastructure.outbox.OrderOutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제·재고예약 관련 Kafka 이벤트를 소비하여 주문 상태를 전이하는 Consumer.
 * <p>
 * 소비 토픽: {@code payment.completed}, {@code payment.failed}, {@code stock.reservation.result}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private static final String GROUP_PAYMENT_COMPLETED = "order-svc-payment-completed-group";
    private static final String GROUP_PAYMENT_FAILED = "order-svc-payment-failed-group";
    private static final String GROUP_STOCK_RESULT = "order-svc-stock-result-group";

    private final OrderRepository orderRepository;
    private final OrderOutboxEventPublisher outboxEventPublisher;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    /** 결제 성공 시 주문 상태를 {@code PAYMENT_COMPLETED}로 전이한다. */
    @KafkaListener(topics = "payment.completed", groupId = GROUP_PAYMENT_COMPLETED)
    @Transactional
    public void handlePaymentCompleted(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_COMPLETED, () -> {
            Long orderId = payload.get("orderId").asLong();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            order.transitionTo(OrderStatus.PAYMENT_COMPLETED);
            log.debug("주문 상태 전이 → PAYMENT_COMPLETED — orderId={}", orderId);
        });
    }

    /**
     * 결제 실패 시 주문을 취소한다.
     * 재고 복구는 Product 가 {@code payment.failed} 를 직접 소비해 release Saga 로 처리한다 (ADR-0012 D3).
     */
    @KafkaListener(topics = "payment.failed", groupId = GROUP_PAYMENT_FAILED)
    @Transactional
    public void handlePaymentFailed(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_FAILED, () -> {
            Long orderId = payload.get("orderId").asLong();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            order.cancel();
            log.debug("결제 실패로 주문 취소 — orderId={}", orderId);
        });
    }

    /**
     * 재고 예약 결과를 소비한다 (ADR-0012 D3).
     * <ul>
     *   <li>{@code reserved=false} → 주문 취소(PENDING→CANCELLED) + {@code order.cancelled} 발행</li>
     *   <li>{@code reserved=true} → 예약 확정 시각 기록(타임아웃 조기취소 방지)</li>
     * </ul>
     */
    @KafkaListener(topics = "stock.reservation.result", groupId = GROUP_STOCK_RESULT)
    @Transactional
    public void handleStockReservationResult(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_STOCK_RESULT, () -> {
            Long orderId = payload.get("orderId").asLong();
            boolean reserved = payload.get("reserved").asBoolean();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            if (reserved) {
                order.confirmReservation();
                log.debug("재고 예약 확정 — orderId={}", orderId);
            } else if (order.getStatus() == OrderStatus.CANCELLED) {
                // cancel-before-create: 주문이 이미 취소된 뒤 reserved=false 가 도착할 수 있다 → 멱등 no-op
                log.debug("예약 실패 수신했으나 주문이 이미 취소됨 — no-op, orderId={}", orderId);
            } else {
                order.cancel();
                outboxEventPublisher.publishOrderCancelled(order);
                log.debug("재고 예약 실패로 주문 취소 — orderId={}", orderId);
            }
        });
    }
}
