package com.peekcart.order.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.global.outbox.dto.ProductUpdatedPayload;
import com.peekcart.order.application.CartCommandService;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.application.dto.AddCartItemCommand;
import com.peekcart.order.application.dto.CreateOrderCommand;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import com.peekcart.order.infrastructure.kafka.ProductPriceCacheConsumer;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Order 로컬 가격 캐시 (CQRS ⑤, strangler-2) <b>소비자 측</b> 통합 테스트.
 * <p>Product peel 이후 본 테스트는 order-side 계약만 검증한다 — product.updated 페이로드를 직접 주입(또는 실제
 * Kafka 리스너 경로로 전달)해 캐시 적재 · version 기반 stale-skip · flush 경계 · 멱등 · schemaVersion 하위호환 ·
 * createOrder 단가 스냅샷 · 캐시 미스 ORD-009 를 본다. product.updated 를 <i>발행</i>하는 product-side(버전 증가
 * 보장)는 product-service 테스트가 검증한다(B1 split 처분).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("가격 캐시 CQRS 소비자 통합 테스트")
class ProductPriceCacheSagaIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired ProductPriceCacheConsumer consumer;
    @Autowired ProductPriceCacheRepository priceCacheRepository;
    @Autowired CartCommandService cartCommandService;
    @Autowired OrderCommandService orderCommandService;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    // DB-per-service(구현 ② PR2): order 스키마엔 categories/products/users 테이블이 없고 교차 FK 도 제거됨(V13).
    // 본 테스트는 order-side 소비자 계약만 보므로(product.updated 직접 주입), 실제 행 없이 임의 ID 참조만 쓴다.
    // product_price_cache/carts/cart_items/orders 는 plain ID(product_id/user_id) 로 동작.
    private final Long categoryId = 7L;
    private final Long userId = 42L;
    private final Long productId = 100L;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("product.updated 소비 → 캐시에 단가 적재")
    void consume_cacheApplied() throws Exception {
        consumer.handleProductUpdated(buildMessage("evt-0", productId, 50_000L, 0));

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(50_000L);
    }

    @Test
    @DisplayName("flush 경계: version 1 이 version 0 캐시를 덮는다 (라운드3 #1 회귀)")
    void update_higherVersion_overwrites() throws Exception {
        consumer.handleProductUpdated(buildMessage("evt-0", productId, 50_000L, 0));   // version 0, price 50000
        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(50_000L);

        consumer.handleProductUpdated(buildMessage("evt-1", productId, 60_000L, 1));   // version 1, price 60000

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(60_000L);
    }

    @Test
    @DisplayName("stale-skip: 더 낮은 version 이벤트는 캐시를 덮지 않는다")
    void lowerVersion_isSkipped() throws Exception {
        consumer.handleProductUpdated(buildMessage("evt-0", productId, 50_000L, 0));
        consumer.handleProductUpdated(buildMessage("evt-1", productId, 60_000L, 1));   // version 1, price 60000

        // 새 eventId 의 과거 version(0) 이벤트 — 멱등이 아니라 stale-skip 으로 무시되어야 한다
        consumer.handleProductUpdated(buildMessage("stale-evt", productId, 99_999L, 0));

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(60_000L);
    }

    @Test
    @DisplayName("멱등: 동일 eventId 중복 소비는 1회만 적용된다")
    void duplicateEvent_appliedOnce() throws Exception {
        consumer.handleProductUpdated(buildMessage("evt-0", productId, 50_000L, 0));

        String msg = buildMessage("dup-evt", productId, 70_000L, 5);
        consumer.handleProductUpdated(msg);
        consumer.handleProductUpdated(msg);   // 중복 — processed_events 로 skip

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(70_000L);
    }

    @Test
    @DisplayName("schemaVersion 하위호환: schemaVersion 누락 메시지도 정상 적용된다")
    void missingSchemaVersion_parsed() throws Exception {
        String legacy = "{\"eventId\":\"legacy-evt\",\"eventType\":\"product.updated\","
                + "\"timestamp\":\"2026-01-01T00:00:00\",\"payload\":{\"productId\":" + productId
                + ",\"name\":\"상품\",\"price\":80000,\"availableStock\":1,\"status\":\"ACTIVE\","
                + "\"categoryId\":" + categoryId + ",\"updatedAt\":\"2026-01-01T00:00:00\",\"version\":9}}";
        consumer.handleProductUpdated(legacy);

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(80_000L);
    }

    @Test
    @DisplayName("e2e: Kafka product.updated→리스너→캐시→createOrder 가 OrderItem 에 캐시 단가 스냅샷")
    void endToEnd_realKafka_orderSnapshotsCachedPrice() throws Exception {
        // 실제 리스너 경로: KafkaTemplate → product.updated 토픽 → ProductPriceCacheConsumer @KafkaListener
        kafkaTemplate.send("product.updated", productId.toString(), buildMessage("e2e-evt", productId, 50_000L, 0));
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(priceCacheRepository.findUnitPrice(productId)).contains(50_000L));

        cartCommandService.addItem(userId, new AddCartItemCommand(productId, 2));
        OrderDetailDto order = orderCommandService.createOrder(
                userId, new CreateOrderCommand("받는사람", "01012345678", "12345", "주소"));

        assertThat(order.items()).hasSize(1);
        assertThat(order.items().get(0).unitPrice()).isEqualTo(50_000L);  // 캐시 단가 스냅샷
        assertThat(order.totalAmount()).isEqualTo(100_000L);
    }

    @Test
    @DisplayName("e2e: 가격 캐시 미스이면 addItem 이 ORD-009 로 실패한다 (strangler-4)")
    void endToEnd_cacheMiss_throwsORD009() {
        // product.updated 미수신 → 로컬 캐시 비어있음. strangler-4 부터 addItem 의 존재 검증이 로컬 캐시 기반이므로
        // 캐시 미수신 상품은 addItem 단계에서 ORD-009 로 거절된다(createOrder 도달 전).
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        cartCommandService.addItem(userId, new AddCartItemCommand(productId, 1)))
                .isInstanceOf(com.peekcart.order.domain.exception.OrderException.class)
                .extracting(e -> ((com.peekcart.order.domain.exception.OrderException) e).getErrorCode())
                .isEqualTo(com.peekcart.global.exception.ErrorCode.ORD_009);
    }

    private String buildMessage(String eventId, Long productId, long price, long version) throws Exception {
        ProductUpdatedPayload payload = new ProductUpdatedPayload(
                productId, "상품", price, 1, "ACTIVE", categoryId, LocalDateTime.now(), version);
        return objectMapper.writeValueAsString(
                new KafkaEventEnvelope(eventId, "product.updated", LocalDateTime.now(), payload));
    }
}
