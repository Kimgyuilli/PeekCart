package com.peekcart.notification.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.notification.application.NotificationCommandService;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * {@code order.cancelled} 취소 사유 분기 단위 테스트 (계획 P6·P7).
 * <p>Order 가 결제 실패발 취소까지 {@code order.cancelled} 로 발행하게 되면서 생기는 중복 알림을
 * 사유로 차단하되, 사유 필드가 없는 <b>구 메시지</b>는 기존대로 알림을 만들어야 한다(하위호환).
 * <p>{@code payment.failed} 알림 타입도 여기서 본다 — 스킵된 취소 알림을 대신하는 쪽이다 (D-047).
 */
@ServiceTest
@DisplayName("NotificationConsumer 취소 사유 분기 · 결제 실패 알림")
class NotificationConsumerTest {

    @InjectMocks NotificationConsumer consumer;
    @Mock NotificationCommandService notificationCommandService;
    @Mock IdempotencyChecker idempotencyChecker;
    @Mock KafkaMessageParser kafkaMessageParser;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("reason=PAYMENT_FAILED → payment.failed 알림과 중복이므로 알림을 만들지 않는다")
    void paymentFailedReason_skipsNotification() {
        stubMessage("PAYMENT_FAILED");

        consumer.handleOrderCancelled("msg");

        then(notificationCommandService).should(never()).createNotification(any(), any(), any());
    }

    @Test
    @DisplayName("reason=USER_REQUESTED → 취소 알림을 생성한다")
    void userRequestedReason_createsNotification() {
        stubMessage("USER_REQUESTED");

        consumer.handleOrderCancelled("msg");

        then(notificationCommandService).should()
                .createNotification(eq(42L), eq(NotificationType.ORDER_CANCELLED), any());
    }

    @Test
    @DisplayName("reason=RESERVATION_FAILED → 재고 예약 실패 취소는 다른 알림이 없으므로 취소 알림을 생성한다")
    void reservationFailedReason_createsNotification() {
        stubMessage("RESERVATION_FAILED");

        consumer.handleOrderCancelled("msg");

        then(notificationCommandService).should()
                .createNotification(eq(42L), eq(NotificationType.ORDER_CANCELLED), any());
    }

    @Test
    @DisplayName("payment.failed → 결제 실패 알림을 생성한다")
    void paymentFailed_createsPaymentFailedNotification() {
        ObjectNode root = om.createObjectNode();
        root.put("eventId", "evt-2");
        ObjectNode payload = root.putObject("payload");
        payload.put("orderId", 1L);
        payload.put("userId", 42L);
        payload.put("amount", 10000L);
        stubParsed(root);

        consumer.handlePaymentFailed("msg");

        then(notificationCommandService).should()
                .createNotification(eq(42L), eq(NotificationType.PAYMENT_FAILED), any());
    }

    @Test
    @DisplayName("reason 필드 부재(구 메시지) → 기존대로 취소 알림을 생성한다 (하위호환)")
    void legacyMessageWithoutReason_createsNotification() {
        stubMessage(null);

        consumer.handleOrderCancelled("msg");

        then(notificationCommandService).should()
                .createNotification(eq(42L), eq(NotificationType.ORDER_CANCELLED), any());
    }

    @Test
    @DisplayName("모르는 reason 값 → 알림을 생성한다 (미래 사유 추가에 침묵하지 않는다)")
    void unknownReason_createsNotification() {
        stubMessage("SOMETHING_NEW");

        consumer.handleOrderCancelled("msg");

        then(notificationCommandService).should()
                .createNotification(eq(42L), eq(NotificationType.ORDER_CANCELLED), any());
    }

    private void stubMessage(String reason) {
        ObjectNode root = om.createObjectNode();
        root.put("eventId", "evt-1");
        ObjectNode payload = root.putObject("payload");
        payload.put("orderId", 1L);
        payload.put("orderNumber", "ORD-001");
        payload.put("userId", 42L);
        if (reason != null) {
            payload.put("reason", reason);
        }
        stubParsed(root);
    }

    private void stubParsed(ObjectNode root) {
        given(kafkaMessageParser.parse("msg")).willReturn((JsonNode) root);
        given(idempotencyChecker.executeIfNew(any(), any(), any())).willAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
    }
}
