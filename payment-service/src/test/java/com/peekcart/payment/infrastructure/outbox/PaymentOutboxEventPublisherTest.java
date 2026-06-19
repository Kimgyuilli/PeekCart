package com.peekcart.payment.infrastructure.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ServiceTest
@DisplayName("PaymentOutboxEventPublisher 단위 테스트 — MDC 캡처 계약")
class PaymentOutboxEventPublisherTest {

    private OutboxEventRepository outboxEventRepository;
    private PaymentOutboxEventPublisher publisher;

    @BeforeEach
    void setUp() {
        MDC.clear();
        outboxEventRepository = mock(OutboxEventRepository.class);
        publisher = new PaymentOutboxEventPublisher(outboxEventRepository,
                new ObjectMapper().registerModule(new JavaTimeModule()));
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 미설정 → 저장된 OutboxEvent 의 traceId / userId 둘 다 null")
    void mdcUnsetCapturesNulls() {
        publisher.publishPaymentFailed(stubPayment(), 99L);

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTraceId()).isNull();
        assertThat(saved.getUserId()).isNull();
    }

    @Test
    @DisplayName("traceId 만 설정 → traceId 영속, userId 는 null")
    void onlyTraceIdCaptured() {
        MDC.put("traceId", "trace-001");

        publisher.publishPaymentFailed(stubPayment(), 99L);

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTraceId()).isEqualTo("trace-001");
        assertThat(saved.getUserId()).isNull();
    }

    @Test
    @DisplayName("traceId / userId 둘 다 설정 → 둘 다 영속")
    void bothCaptured() {
        MDC.put("traceId", "trace-001");
        MDC.put("userId", "42");

        publisher.publishPaymentFailed(stubPayment(), 99L);

        OutboxEvent saved = captureSaved();
        assertThat(saved.getTraceId()).isEqualTo("trace-001");
        assertThat(saved.getUserId()).isEqualTo("42");
    }

    @Test
    @DisplayName("publishPaymentRequested → payment.requested 이벤트가 orderId aggregateId 로 저장된다")
    void publishPaymentRequested_savesEvent() {
        Payment payment = mock(Payment.class);
        given(payment.getOrderId()).willReturn(1L);

        publisher.publishPaymentRequested(payment, 42L);

        OutboxEvent saved = captureSaved();
        assertThat(saved.getEventType()).isEqualTo("payment.requested");
        assertThat(saved.getAggregateType()).isEqualTo("PAYMENT");
        assertThat(saved.getAggregateId()).isEqualTo("1");  // orderId
    }

    private OutboxEvent captureSaved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private static Payment stubPayment() {
        Payment payment = mock(Payment.class);
        given(payment.getId()).willReturn(10L);
        given(payment.getOrderId()).willReturn(1L);
        given(payment.getPaymentKey()).willReturn("pay-key-001");
        given(payment.getAmount()).willReturn(15000L);
        return payment;
    }
}
