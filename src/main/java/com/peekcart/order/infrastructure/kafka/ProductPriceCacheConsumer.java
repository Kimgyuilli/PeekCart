package com.peekcart.order.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code product.updated} 를 소비하여 Order 로컬 가격 캐시를 갱신하는 Consumer (CQRS ⑤, strangler-2).
 * <p>
 * 멱등: {@code processed_events} 로 중복 이벤트를 1회만 적용한다.
 * 순서: 파티션 키=productId 로 정상 경로는 per-partition in-order 이며, replay/재정렬은
 * {@code source_version < version} 비교로 과거 version 덮어쓰기를 막는다.
 * Order 는 payload 중 {@code price}/{@code version} 만 소비한다(나머지 필드 무시).
 * <p>
 * {@code processed_events} retention 은 {@code product.updated} topic retention·DLQ replay 창
 * 이상이어야 한다(ADR-0012 D5/§80-84) — 공유 retention 정책을 따른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProductPriceCacheConsumer {

    private static final String GROUP_PRODUCT_UPDATED = "order-svc-product-updated-group";

    private final ProductPriceCacheRepository priceCacheRepository;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    @KafkaListener(topics = "product.updated", groupId = GROUP_PRODUCT_UPDATED)
    @Transactional
    public void handleProductUpdated(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PRODUCT_UPDATED, () -> {
            Long productId = payload.get("productId").asLong();
            long price = payload.get("price").asLong();
            long version = payload.get("version").asLong();
            priceCacheRepository.applyUpdate(productId, price, version);
            log.debug("가격 캐시 갱신 — productId={}, version={}", productId, version);
        });
    }
}
