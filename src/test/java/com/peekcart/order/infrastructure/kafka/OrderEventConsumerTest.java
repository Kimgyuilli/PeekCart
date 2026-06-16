package com.peekcart.order.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.order.infrastructure.outbox.OrderOutboxEventPublisher;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ServiceTest
@DisplayName("OrderEventConsumer.handleStockReservationResult 단위 테스트")
class OrderEventConsumerTest {

    @InjectMocks OrderEventConsumer consumer;
    @Mock OrderRepository orderRepository;
    @Mock OrderOutboxEventPublisher outboxEventPublisher;
    @Mock IdempotencyChecker idempotencyChecker;
    @Mock KafkaMessageParser kafkaMessageParser;

    private final ObjectMapper om = new ObjectMapper();

    private void stubMessage(boolean reserved) {
        ObjectNode root = om.createObjectNode();
        root.put("eventId", "evt-1");
        ObjectNode payload = root.putObject("payload");
        payload.put("orderId", OrderFixture.DEFAULT_ORDER_ID);
        payload.put("reserved", reserved);
        given(kafkaMessageParser.parse("msg")).willReturn((JsonNode) root);
        given(idempotencyChecker.executeIfNew(any(), any(), any())).willAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
    }

    @Test
    @DisplayName("reserved=false + PENDING → 주문 취소 + order.cancelled 발행")
    void reservedFalse_pending_cancels() {
        Order order = OrderFixture.orderWithId();
        given(orderRepository.findById(OrderFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(order));
        stubMessage(false);

        consumer.handleStockReservationResult("msg");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        then(outboxEventPublisher).should().publishOrderCancelled(order);
    }

    @Test
    @DisplayName("reserved=false + 이미 CANCELLED → 멱등 no-op (ORD-002 예외 없음, 발행 없음)")
    void reservedFalse_alreadyCancelled_noop() {
        Order order = OrderFixture.orderWithId();
        order.cancel();  // 이미 취소된 주문 (cancel-before-create)
        given(orderRepository.findById(OrderFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(order));
        stubMessage(false);

        consumer.handleStockReservationResult("msg");  // 예외 없이 통과

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        then(outboxEventPublisher).should(never()).publishOrderCancelled(any());
    }

    @Test
    @DisplayName("reserved=true → 예약 확정 기록 (취소 발행 없음)")
    void reservedTrue_confirms() {
        Order order = OrderFixture.orderWithId();
        given(orderRepository.findById(OrderFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(order));
        stubMessage(true);

        consumer.handleStockReservationResult("msg");

        assertThat(order.getReservationConfirmedAt()).isNotNull();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
        then(outboxEventPublisher).should(never()).publishOrderCancelled(any());
    }
}
