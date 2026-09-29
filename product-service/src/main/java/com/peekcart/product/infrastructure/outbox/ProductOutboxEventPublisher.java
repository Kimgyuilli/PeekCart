package com.peekcart.product.infrastructure.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.MdcSnapshot;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.CompensationReason;
import com.peekcart.global.outbox.dto.CompensationRequestedPayload;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.global.outbox.dto.ProductUpdatedPayload;
import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.global.outbox.dto.StockReservationResultPayload;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Product 도메인의 Outbox 이벤트 발행자 (ADR-0012 D3/D4 · CQRS).
 * 공유 {@link OutboxEventRepository} 를 재사용한다. 파티션 키(aggregateId)는 토픽별로 다르다 —
 * {@code stock.reservation.result} 는 {@code orderId}, {@code product.updated} 는 {@code productId}(ADR-0012 D2).
 */
@Component
@RequiredArgsConstructor
public class ProductOutboxEventPublisher {

    private static final String AGGREGATE_TYPE = "PRODUCT";
    private static final String STOCK_RESERVATION_RESULT = "stock.reservation.result";
    private static final String PRODUCT_UPDATED = "product.updated";
    private static final String STOCK_COMPENSATION_REQUESTED = "stock.compensation.requested";

    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;

    /**
     * 재고 예약 결과를 발행한다.
     *
     * @param reason               실패 사유 (성공 시 null)
     * @param reservationExpiresAt 예약 lease 만료 시각 (실패 시 null)
     */
    public void publishStockReservationResult(Long orderId, boolean reserved,
                                              List<ReservedItemPayload> items, String reason,
                                              LocalDateTime reservationExpiresAt) {
        StockReservationResultPayload payload = new StockReservationResultPayload(
                orderId, reserved, items, reason, LocalDateTime.now(), reservationExpiresAt);

        MdcSnapshot.Snapshot mdc = MdcSnapshot.current();
        OutboxEvent outboxEvent = OutboxEvent.create(AGGREGATE_TYPE, orderId.toString(),
                STOCK_RESERVATION_RESULT, mdc.traceId(), mdc.userId(),
                eventId -> serialize(new KafkaEventEnvelope(eventId, STOCK_RESERVATION_RESULT,
                        LocalDateTime.now(), payload)));
        outboxEventRepository.save(outboxEvent);
    }

    /**
     * 상품 변경(생성/수정/판매중단)을 발행한다 (ADR-0012 D2 · CQRS).
     * <p>
     * 파티션 키=productId 로 동일 상품 이벤트의 per-partition 순서를 보장한다.
     * {@code version} 은 호출 전 flush 로 증가된 {@link Product#getVersion()} 이어야 한다(순서 키).
     *
     * @param availableStock 발행 시점 가용 재고 (Inventory 조회 값)
     */
    public void publishProductUpdated(Product product, int availableStock) {
        ProductUpdatedPayload payload = new ProductUpdatedPayload(
                product.getId(),
                product.getName(),
                product.getPrice(),
                availableStock,
                mapStatus(product.getStatus()),
                product.getCategory().getId(),
                LocalDateTime.now(),
                product.getVersion());

        MdcSnapshot.Snapshot mdc = MdcSnapshot.current();
        OutboxEvent outboxEvent = OutboxEvent.create(AGGREGATE_TYPE, product.getId().toString(),
                PRODUCT_UPDATED, mdc.traceId(), mdc.userId(),
                eventId -> serialize(new KafkaEventEnvelope(eventId, PRODUCT_UPDATED,
                        LocalDateTime.now(), payload)));
        outboxEventRepository.save(outboxEvent);
    }

    /**
     * 환불 보상을 요청한다 (ADR-0018 D1). <b>감지 marker 기록과 같은 트랜잭션</b>에서 호출돼야 한다 —
     * 부분 커밋은 "감지했는데 요청이 없는" 영구 미결을 만든다.
     *
     * <p>aggregateType/aggregateId 는 {@code PRODUCT}/{@code orderId} 로 고정한다. backfill SQL 의
     * {@code NOT EXISTS} 조건이 <b>같은 키</b>를 쓰기 때문에, 여기를 바꾸면 backfill 멱등이 깨진다.
     *
     * @param detectedAt 감지 시각 (발행 시각이 아니다)
     */
    public void publishCompensationRequested(Long orderId, CompensationReason reason, LocalDateTime detectedAt) {
        CompensationRequestedPayload payload = new CompensationRequestedPayload(orderId, reason, detectedAt);

        MdcSnapshot.Snapshot mdc = MdcSnapshot.current();
        OutboxEvent outboxEvent = OutboxEvent.create(AGGREGATE_TYPE, orderId.toString(),
                STOCK_COMPENSATION_REQUESTED, mdc.traceId(), mdc.userId(),
                eventId -> serialize(new KafkaEventEnvelope(eventId, STOCK_COMPENSATION_REQUESTED,
                        LocalDateTime.now(), payload)));
        outboxEventRepository.save(outboxEvent);
    }

    /** ProductStatus → ADR-0012 D2 계약 값 매핑. */
    private static String mapStatus(ProductStatus status) {
        return switch (status) {
            case ON_SALE -> "ACTIVE";
            case DISCONTINUED -> "INACTIVE";
            case SOLD_OUT -> "SOLD_OUT";
        };
    }

    private String serialize(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Outbox 이벤트 직렬화 실패", e);
        }
    }
}
