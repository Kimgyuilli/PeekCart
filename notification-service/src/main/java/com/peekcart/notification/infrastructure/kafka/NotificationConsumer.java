package com.peekcart.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.notification.application.NotificationCommandService;
import com.peekcart.notification.domain.model.NotificationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문/결제 관련 Kafka 이벤트를 소비하여 알림을 생성하는 Consumer.
 * <p>
 * 소비 토픽: {@code order.created}, {@code payment.completed}, {@code payment.failed}, {@code order.cancelled}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationConsumer {

    private static final String GROUP_ORDER_CREATED = "notification-svc-order-created-group";
    private static final String GROUP_PAYMENT_COMPLETED = "notification-svc-payment-completed-group";
    private static final String GROUP_PAYMENT_FAILED = "notification-svc-payment-failed-group";
    private static final String GROUP_ORDER_CANCELLED = "notification-svc-order-cancelled-group";

    private final NotificationCommandService notificationCommandService;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    /** 주문 생성 알림을 발송한다. */
    @KafkaListener(topics = "order.created", groupId = GROUP_ORDER_CREATED)
    @Transactional
    public void handleOrderCreated(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_ORDER_CREATED, () -> {
            Long userId = payload.get("userId").asLong();
            String orderNumber = payload.get("orderNumber").asText();
            long totalAmount = payload.get("totalAmount").asLong();
            String msg = String.format("주문이 생성되었습니다. [주문번호: %s, 금액: %,d원]", orderNumber, totalAmount);
            notificationCommandService.createNotification(userId, NotificationType.ORDER_CREATED, msg);
        });
    }

    /** 결제 완료 알림을 발송한다. */
    @KafkaListener(topics = "payment.completed", groupId = GROUP_PAYMENT_COMPLETED)
    @Transactional
    public void handlePaymentCompleted(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_COMPLETED, () -> {
            Long userId = payload.get("userId").asLong();
            Long orderId = payload.get("orderId").asLong();
            long amount = payload.get("amount").asLong();
            String method = payload.get("method").asText();
            String msg = String.format("결제가 완료되었습니다. [주문 ID: %d, 금액: %,d원, 결제수단: %s]",
                    orderId, amount, method);
            notificationCommandService.createNotification(userId, NotificationType.PAYMENT_COMPLETED, msg);
        });
    }

    /** 결제 실패 알림을 발송한다. */
    @KafkaListener(topics = "payment.failed", groupId = GROUP_PAYMENT_FAILED)
    @Transactional
    public void handlePaymentFailed(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_FAILED, () -> {
            Long userId = payload.get("userId").asLong();
            Long orderId = payload.get("orderId").asLong();
            long amount = payload.get("amount").asLong();
            String msg = String.format("결제에 실패했습니다. [주문 ID: %d, 금액: %,d원]", orderId, amount);
            notificationCommandService.createNotification(userId, NotificationType.PAYMENT_FAILED, msg);
        });
    }

    /** 주문 취소 알림을 발송한다. */
    @KafkaListener(topics = "order.cancelled", groupId = GROUP_ORDER_CANCELLED)
    @Transactional
    public void handleOrderCancelled(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_ORDER_CANCELLED, () -> {
            Long userId = payload.get("userId").asLong();
            String orderNumber = payload.get("orderNumber").asText();
            String msg = String.format("주문이 취소되었습니다. [주문번호: %s]", orderNumber);
            notificationCommandService.createNotification(userId, NotificationType.ORDER_CANCELLED, msg);
        });
    }
}
