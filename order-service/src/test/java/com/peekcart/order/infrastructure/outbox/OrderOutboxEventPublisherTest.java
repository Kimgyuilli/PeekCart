package com.peekcart.order.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.order.domain.model.Order;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ServiceTest
@DisplayName("OrderOutboxEventPublisher 단위 테스트 — MDC 캡처 계약")
class OrderOutboxEventPublisherTest {

    private OutboxEventRepository outboxEventRepository;
    private OrderOutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        MDC.clear();
        outboxEventRepository = mock(OutboxEventRepository.class);
        publisher = new OrderOutboxEventPublisher(outboxEventRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 미설정 → 저장된 OutboxEvent 의 traceId / userId 둘 다 null")
    void mdcUnsetCapturesNulls() {
        publisher.publishOrderCancelled(stubOrder());

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTraceId()).isNull();
        assertThat(saved.getUserId()).isNull();
    }

    @Test
    @DisplayName("traceId 만 설정 → traceId 영속, userId 는 null")
    void onlyTraceIdCaptured() {
        MDC.put("traceId", "trace-001");

        publisher.publishOrderCancelled(stubOrder());

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTraceId()).isEqualTo("trace-001");
        assertThat(saved.getUserId()).isNull();
    }

    @Test
    @DisplayName("traceId / userId 둘 다 설정 → 둘 다 영속")
    void bothCaptured() {
        MDC.put("traceId", "trace-001");
        MDC.put("userId", "42");

        publisher.publishOrderCancelled(stubOrder());

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTraceId()).isEqualTo("trace-001");
        assertThat(saved.getUserId()).isEqualTo("42");
    }

    private OutboxEvent captureSaved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Order stubOrder() {
        Order order = mock(Order.class);
        given(order.getId()).willReturn(1L);
        given(order.getOrderNumber()).willReturn("ORD-001");
        given(order.getUserId()).willReturn(99L);
        return order;
    }
}
