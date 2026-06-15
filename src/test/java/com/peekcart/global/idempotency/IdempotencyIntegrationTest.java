package com.peekcart.global.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.OutboxPollingService;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.infrastructure.outbox.OrderOutboxEventPublisher;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "spring.task.scheduling.pool.size=1")
@Import(IntegrationTestConfig.class)
@DisplayName("Consumer 멱등성 통합 테스트")
class IdempotencyIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired OrderOutboxEventPublisher orderOutboxEventPublisher;
    @Autowired OutboxPollingService outboxPollingService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired ProcessedEventJpaRepository processedEventJpaRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    private Long userId;
    private Long productId;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // [PR2b] User 도메인 peel → root 는 users 행을 native insert 로 시드(FK 충족, root-observable)
        em.createNativeQuery(
                        "INSERT INTO users (email, password_hash, name, role, created_at, updated_at) " +
                                "VALUES ('test@peekcart.com', 'hashed-pw', '테스트유저', 'USER', NOW(6), NOW(6))")
                .executeUpdate();
        em.flush();
        userId = ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = 'test@peekcart.com'")
                .getSingleResult()).longValue();

        Category category = Category.create("테스트 카테고리", null);
        em.persist(category);
        em.flush();

        Product product = Product.create(category, "테스트 상품", "설명", 50_000L, null);
        em.persist(product);
        em.flush();
        productId = product.getId();

        Inventory inventory = Inventory.create(product, 100);
        em.persist(inventory);

        em.getTransaction().commit();
        em.close();
    }

    @Test
    @DisplayName("동일 이벤트를 같은 consumer group에서 2회 소비하면 1회만 처리된다")
    void duplicateEvent_sameConsumerGroup_processedOnce() {
        // given: order.created 이벤트 발행 → Consumer 처리 대기
        Order order = persistOrder();
        orderOutboxEventPublisher.publishOrderCreated(order);

        // Outbox에서 eventId 추출
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(100);
        assertThat(pending).hasSize(1);
        String eventId = pending.get(0).getEventId();
        String payload = pending.get(0).getPayload();

        // Kafka로 발행 + Consumer 처리 대기
        outboxPollingService.pollAndPublish();

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(paymentRepository.findByOrderId(order.getId())).isPresent();
        });

        // 처리 결과 기록
        long paymentCountBefore = countPaymentsByOrderId(order.getId());

        // when: 동일 eventId 메시지를 KafkaTemplate으로 직접 재전송
        kafkaTemplate.send("order.created", order.getId().toString(), payload);

        // then: 충분히 대기 후에도 Payment 수 변화 없음 (consumer 멱등성)
        await().during(3, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countPaymentsByOrderId(order.getId())).isEqualTo(paymentCountBefore));
    }

    @Test
    @DisplayName("order.created 소비 시 consumer group 단위로 processed_events 에 기록한다 (notification 그룹은 notification-service 검증)")
    void event_recordedPerConsumerGroup() {
        // given: order.created → 루트 PaymentEventConsumer 소비 (NotificationConsumer 는 notification-service 로 peel)
        Order order = persistOrder();
        orderOutboxEventPublisher.publishOrderCreated(order);

        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(100);
        String eventId = pending.get(0).getEventId();

        // when
        outboxPollingService.pollAndPublish();

        // then: payment consumer group 처리 완료
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(order.getId())).isPresent());

        // processed_events 에 payment consumer group 으로 기록 (consumer group 단위 멱등성 키)
        List<ProcessedEvent> processedEvents = processedEventJpaRepository.findAll().stream()
                .filter(pe -> pe.getEventId().equals(eventId))
                .toList();
        assertThat(processedEvents)
                .extracting(ProcessedEvent::getConsumerGroup)
                .contains("payment-svc-order-created-group");
    }

    // ── 헬퍼 메서드 ──

    private Order persistOrder() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        List<OrderItemData> items = List.of(new OrderItemData(productId, 2, 50_000L));
        Order order = Order.create(userId, "ORD-TEST-" + System.nanoTime(),
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", items);
        order.transitionTo(OrderStatus.PAYMENT_REQUESTED);

        em.persist(order);
        em.getTransaction().commit();
        em.close();
        return order;
    }

    private long countPaymentsByOrderId(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                            "SELECT COUNT(p) FROM Payment p WHERE p.orderId = :orderId", Long.class)
                    .setParameter("orderId", orderId)
                    .getSingleResult();
        } finally {
            em.close();
        }
    }
}
