package com.peekcart.product.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.MdcSnapshot;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.global.outbox.dto.StockReservationResultPayload;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Product 도메인의 Outbox 이벤트 발행자 (ADR-0012 D3/D4).
 * 공유 {@link OutboxEventRepository} 를 재사용하며, 파티션 키(aggregateId)는 {@code orderId} 다.
 */
@Component
@RequiredArgsConstructor
public class ProductOutboxEventPublisher {

    private static final String AGGREGATE_TYPE = "PRODUCT";
    private static final String STOCK_RESERVATION_RESULT = "stock.reservation.result";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 재고 예약 결과를 발행한다.
     *
     * @param reason 실패 사유 (성공 시 null)
     */
    public void publishStockReservationResult(Long orderId, boolean reserved,
                                              List<ReservedItemPayload> items, String reason) {
        StockReservationResultPayload payload = new StockReservationResultPayload(
                orderId, reserved, items, reason, LocalDateTime.now());

        MdcSnapshot.Snapshot mdc = MdcSnapshot.current();
        OutboxEvent outboxEvent = OutboxEvent.create(AGGREGATE_TYPE, orderId.toString(),
                STOCK_RESERVATION_RESULT, mdc.traceId(), mdc.userId(),
                eventId -> serialize(new KafkaEventEnvelope(eventId, STOCK_RESERVATION_RESULT,
                        LocalDateTime.now(), payload)));
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
