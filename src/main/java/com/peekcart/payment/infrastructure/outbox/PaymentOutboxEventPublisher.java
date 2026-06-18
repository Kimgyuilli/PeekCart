package com.peekcart.payment.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.MdcSnapshot;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.global.outbox.dto.PaymentCompletedPayload;
import com.peekcart.global.outbox.dto.PaymentFailedPayload;
import com.peekcart.global.outbox.dto.PaymentRequestedPayload;
import com.peekcart.payment.domain.model.Payment;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
public class PaymentOutboxEventPublisher {

    private static final String AGGREGATE_TYPE = "PAYMENT";
    private static final String PAYMENT_COMPLETED = "payment.completed";
    private static final String PAYMENT_FAILED = "payment.failed";
    private static final String PAYMENT_REQUESTED = "payment.requested";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /** 결제 시작을 Order 에 알린다 (동기 OrderPort.transitionToPaymentRequested 대체). */
    public void publishPaymentRequested(Payment payment, Long userId) {
        PaymentRequestedPayload payload = new PaymentRequestedPayload(payment.getOrderId(), userId);
        saveOutboxEvent(PAYMENT_REQUESTED, payment.getOrderId().toString(), payload);
    }

    public void publishPaymentCompleted(Payment payment, Long userId) {
        PaymentCompletedPayload payload = new PaymentCompletedPayload(
                payment.getId(),
                payment.getOrderId(),
                userId,
                payment.getPaymentKey(),
                payment.getAmount(),
                payment.getMethod(),
                payment.getApprovedAt());

        saveOutboxEvent(PAYMENT_COMPLETED, payment.getOrderId().toString(), payload);
    }

    public void publishPaymentFailed(Payment payment, Long userId) {
        PaymentFailedPayload payload = new PaymentFailedPayload(
                payment.getId(),
                payment.getOrderId(),
                userId,
                payment.getPaymentKey(),
                payment.getAmount());

        saveOutboxEvent(PAYMENT_FAILED, payment.getOrderId().toString(), payload);
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
