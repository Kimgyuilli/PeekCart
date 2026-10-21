package com.peekcart.product.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.product.application.StockReservationService;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/**
 * {@code order.cancelled} 계약 확장(items·reason)에 대한 소비자 내성 테스트.
 * <p>Product 의 재고 복구는 예약 원장 기반(orderId 단위 CAS)이라 payload 확장과 무관해야 한다 —
 * 신·구 메시지 모두 같은 release 를 부른다.
 */
@ServiceTest
@DisplayName("StockReleaseConsumer 계약 확장 내성")
class StockReleaseConsumerTest {

    @InjectMocks StockReleaseConsumer consumer;
    @Mock StockReservationService reservationService;
    @Mock IdempotencyChecker idempotencyChecker;
    @Mock KafkaMessageParser kafkaMessageParser;

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("구 메시지(items·reason 부재) → orderId 로 release")
    void legacyPayload_releases() {
        stubOrderCancelled(false);

        consumer.handleOrderCancelled("msg");

        then(reservationService).should().release(7L);
    }

    @Test
    @DisplayName("신 메시지(items·reason 포함) → 동일하게 orderId 로 release")
    void extendedPayload_releases() {
        stubOrderCancelled(true);

        consumer.handleOrderCancelled("msg");

        then(reservationService).should().release(7L);
    }

    private void stubOrderCancelled(boolean extended) {
        ObjectNode root = om.createObjectNode();
        root.put("eventId", "evt-1");
        ObjectNode payload = root.putObject("payload");
        payload.put("orderId", 7L);
        payload.put("orderNumber", "ORD-001");
        payload.put("userId", 42L);
        if (extended) {
            payload.put("reason", "USER_REQUESTED");
            ObjectNode item = payload.putArray("items").addObject();
            item.put("productId", 11L);
            item.put("quantity", 2);
        }
        given(kafkaMessageParser.parse("msg")).willReturn((JsonNode) root);
        given(idempotencyChecker.executeIfNew(any(), any(), any())).willAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
    }
}
