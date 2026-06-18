package com.peekcart.order.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.OutboxPollingService;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.global.outbox.dto.ProductUpdatedPayload;
import com.peekcart.order.application.CartCommandService;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.application.dto.AddCartItemCommand;
import com.peekcart.order.application.dto.CreateOrderCommand;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import com.peekcart.order.infrastructure.kafka.ProductPriceCacheConsumer;
import com.peekcart.product.application.ProductCommandService;
import com.peekcart.product.application.dto.UpdateProductCommand;
import com.peekcart.product.domain.model.Category;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import com.peekcart.product.application.dto.CreateProductCommand;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Order 로컬 가격 캐시 (CQRS ⑤, strangler-2) 통합 테스트.
 * 실 DB+마이그레이션(V7)에서 product.updated 발행 → 캐시 적재 → version 기반 stale-skip ·
 * flush 경계(version 0→1 덮어쓰기) · 멱등 · schemaVersion 하위호환을 검증한다.
 */
@SpringBootTest
@Testcontainers
@Import(IntegrationTestConfig.class)
@DisplayName("가격 캐시 CQRS 통합 테스트")
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

    @Autowired ProductCommandService productCommandService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxPollingService outboxPollingService;
    @Autowired ProductPriceCacheConsumer consumer;
    @Autowired ProductPriceCacheRepository priceCacheRepository;
    @Autowired CartCommandService cartCommandService;
    @Autowired OrderCommandService orderCommandService;
    @Autowired ObjectMapper objectMapper;

    private Long categoryId;
    private Long userId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Category category = Category.create("카테고리", null);
        em.persist(category);
        em.flush();
        // User 도메인 peel → root 는 users 행을 native insert 로 시드(carts FK fk_carts_user 충족, root-observable)
        em.createNativeQuery(
                "INSERT INTO users (email, password_hash, name, role, created_at, updated_at) "
                        + "VALUES ('cache@peekcart.com', 'hashed-pw', '캐시유저', 'USER', NOW(6), NOW(6))")
                .executeUpdate();
        userId = ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = 'cache@peekcart.com'")
                .getSingleResult()).longValue();
        em.getTransaction().commit();
        categoryId = category.getId();
        em.close();
    }

    @Test
    @DisplayName("create → product.updated 발행 → consume → 캐시에 단가 적재")
    void create_publishes_and_cacheApplied() {
        Long productId = productCommandService.create(
                new CreateProductCommand(categoryId, "상품", "설명", 50_000L, null, 100)).id();

        consumeLatestProductUpdated();

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(50_000L);
    }

    @Test
    @DisplayName("flush 경계: 가격 update 의 version 1 이 version 0 캐시를 덮는다 (라운드3 #1 회귀)")
    void update_higherVersion_overwrites() {
        Long productId = productCommandService.create(
                new CreateProductCommand(categoryId, "상품", "설명", 50_000L, null, 100)).id();
        consumeLatestProductUpdated();                       // version 0, price 50000
        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(50_000L);

        productCommandService.update(productId,
                new UpdateProductCommand(categoryId, "상품", "설명", 60_000L, null));
        consumeLatestProductUpdated();                       // version 1, price 60000

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(60_000L);
    }

    @Test
    @DisplayName("stale-skip: 더 낮은 version 이벤트는 캐시를 덮지 않는다")
    void lowerVersion_isSkipped() throws Exception {
        Long productId = productCommandService.create(
                new CreateProductCommand(categoryId, "상품", "설명", 50_000L, null, 100)).id();
        consumeLatestProductUpdated();
        productCommandService.update(productId,
                new UpdateProductCommand(categoryId, "상품", "설명", 60_000L, null));
        consumeLatestProductUpdated();                       // version 1, price 60000

        // 새 eventId 의 과거 version(0) 이벤트 — 멱등이 아니라 stale-skip 으로 무시되어야 한다
        consumer.handleProductUpdated(buildMessage("stale-evt", productId, 99_999L, 0));

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(60_000L);
    }

    @Test
    @DisplayName("멱등: 동일 eventId 중복 소비는 1회만 적용된다")
    void duplicateEvent_appliedOnce() throws Exception {
        Long productId = productCommandService.create(
                new CreateProductCommand(categoryId, "상품", "설명", 50_000L, null, 100)).id();
        consumeLatestProductUpdated();

        String msg = buildMessage("dup-evt", productId, 70_000L, 5);
        consumer.handleProductUpdated(msg);
        consumer.handleProductUpdated(msg);   // 중복 — processed_events 로 skip

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(70_000L);
    }

    @Test
    @DisplayName("schemaVersion 하위호환: schemaVersion 누락 메시지도 정상 적용된다")
    void missingSchemaVersion_parsed() throws Exception {
        Long productId = productCommandService.create(
                new CreateProductCommand(categoryId, "상품", "설명", 50_000L, null, 100)).id();
        consumeLatestProductUpdated();

        String legacy = "{\"eventId\":\"legacy-evt\",\"eventType\":\"product.updated\","
                + "\"timestamp\":\"2026-01-01T00:00:00\",\"payload\":{\"productId\":" + productId
                + ",\"name\":\"상품\",\"price\":80000,\"availableStock\":1,\"status\":\"ACTIVE\","
                + "\"categoryId\":" + categoryId + ",\"updatedAt\":\"2026-01-01T00:00:00\",\"version\":9}}";
        consumer.handleProductUpdated(legacy);

        assertThat(priceCacheRepository.findUnitPrice(productId)).contains(80_000L);
    }

    @Test
    @DisplayName("e2e: create→outbox relay→Kafka listener→캐시→createOrder 가 OrderItem 에 캐시 단가 스냅샷")
    void endToEnd_realKafka_orderSnapshotsCachedPrice() {
        Long productId = productCommandService.create(
                new CreateProductCommand(categoryId, "상품", "설명", 50_000L, null, 100)).id();

        // 실제 발행 경로: OutboxPollingService → Kafka → ProductPriceCacheConsumer @KafkaListener
        outboxPollingService.pollAndPublish();
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
        // product.updated 미수신(relay 안 함) → 로컬 캐시 비어있음. strangler-4 부터 addItem 의 존재
        // 검증이 로컬 캐시 기반이므로 캐시 미수신 상품은 addItem 단계에서 ORD-009 로 거절된다(createOrder 도달 전).
        Long productId = productCommandService.create(
                new CreateProductCommand(categoryId, "상품2", "설명", 30_000L, null, 100)).id();

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        cartCommandService.addItem(userId, new AddCartItemCommand(productId, 1)))
                .isInstanceOf(com.peekcart.order.domain.exception.OrderException.class)
                .extracting(e -> ((com.peekcart.order.domain.exception.OrderException) e).getErrorCode())
                .isEqualTo(com.peekcart.global.exception.ErrorCode.ORD_009);
    }

    /** create/update 가 발행한 가장 최신 product.updated outbox 이벤트를 consumer 에 전달한다. */
    private void consumeLatestProductUpdated() {
        String message = outboxEventRepository.findPendingEvents(100).stream()
                .filter(e -> e.getEventType().equals("product.updated"))
                .reduce((first, second) -> second)   // 마지막 = 최신
                .orElseThrow(() -> new IllegalStateException("product.updated 이벤트 없음"))
                .getPayload();
        consumer.handleProductUpdated(message);
    }

    private String buildMessage(String eventId, Long productId, long price, long version) throws Exception {
        ProductUpdatedPayload payload = new ProductUpdatedPayload(
                productId, "상품", price, 1, "ACTIVE", categoryId, LocalDateTime.now(), version);
        return objectMapper.writeValueAsString(
                new KafkaEventEnvelope(eventId, "product.updated", LocalDateTime.now(), payload));
    }
}
