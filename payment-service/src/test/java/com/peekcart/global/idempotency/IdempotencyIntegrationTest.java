package com.peekcart.global.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.global.outbox.dto.OrderCreatedPayload;
import com.peekcart.payment.domain.repository.PaymentRepository;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Consumer 멱등성 통합 테스트 (Order peel PR-a 후 — root 잔류 {@code PaymentEventConsumer} 대상).
 *
 * <p>root {@code PaymentEventConsumer.handleOrderCreated} 가 {@code order.created} 를 소비해 {@code PENDING}
 * Payment 를 생성하는 경로의 멱등성(동일 eventId 재소비 시 1회만 처리·consumer group 단위 processed_events 기록)을 검증한다.
 * order.created 발행자는 order-service 로 peel 되었으므로, 본 테스트는 {@link KafkaTemplate} 로 envelope 을
 * 직접 produce 해 root consumer 만 독립 검증한다(cross-service 의존 제거).</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"spring.task.scheduling.pool.size=1", "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
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

    @Autowired PaymentRepository paymentRepository;
    @Autowired ProcessedEventJpaRepository processedEventJpaRepository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;

    private Long userId;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        // [PR2b] User 도메인 peel → root 는 users 행을 native insert 로 시드(orders FK fk_orders_user 충족, root-observable)
        em.createNativeQuery(
                        "INSERT INTO users (email, password_hash, name, role, created_at, updated_at) " +
                                "VALUES ('test@peekcart.com', 'hashed-pw', '테스트유저', 'USER', NOW(6), NOW(6))")
                .executeUpdate();
        em.flush();
        userId = ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = 'test@peekcart.com'")
                .getSingleResult()).longValue();
        em.getTransaction().commit();
        em.close();
    }

    @Test
    @DisplayName("동일 이벤트를 같은 consumer group에서 2회 소비하면 1회만 처리된다")
    void duplicateEvent_sameConsumerGroup_processedOnce() {
        // given: order.created 를 KafkaTemplate 로 produce → root PaymentEventConsumer 소비 대기
        Long orderId = seedOrder();
        String eventId = UUID.randomUUID().toString();
        String payload = orderCreatedEnvelope(eventId, orderId);
        kafkaTemplate.send("order.created", orderId.toString(), payload);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(orderId)).isPresent());

        long paymentCountBefore = countPaymentsByOrderId(orderId);

        // when: 동일 eventId 메시지를 직접 재전송
        kafkaTemplate.send("order.created", orderId.toString(), payload);

        // then: 충분히 대기 후에도 Payment 수 변화 없음 (consumer 멱등성)
        await().during(3, TimeUnit.SECONDS).atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countPaymentsByOrderId(orderId)).isEqualTo(paymentCountBefore));
    }

    @Test
    @DisplayName("order.created 소비 시 consumer group 단위로 processed_events 에 기록한다 (notification 그룹은 notification-service 검증)")
    void event_recordedPerConsumerGroup() {
        // given: order.created → 루트 PaymentEventConsumer 소비 (NotificationConsumer 는 notification-service 로 peel)
        Long orderId = seedOrder();
        String eventId = UUID.randomUUID().toString();
        kafkaTemplate.send("order.created", orderId.toString(), orderCreatedEnvelope(eventId, orderId));

        // then: payment consumer group 처리 완료
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(paymentRepository.findByOrderId(orderId)).isPresent());

        // processed_events 에 payment consumer group 으로 기록 (consumer group 단위 멱등성 키)
        List<ProcessedEvent> processedEvents = processedEventJpaRepository.findAll().stream()
                .filter(pe -> pe.getEventId().equals(eventId))
                .toList();
        assertThat(processedEvents)
                .extracting(ProcessedEvent::getConsumerGroup)
                .contains("payment-svc-order-created-group");
    }

    // ── 헬퍼 메서드 ──

    /** payments.order_id FK(fk_payments_order) 충족용 orders 행 시드 → orderId 반환. */
    private Long seedOrder() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        String orderNumber = "ORD-TEST-" + System.nanoTime();
        em.createNativeQuery(
                        "INSERT INTO orders (user_id, order_number, total_amount, status, receiver_name, phone, zipcode, address, ordered_at) " +
                                "VALUES (?1, ?2, 100000, 'PAYMENT_REQUESTED', '홍길동', '010-1234-5678', '12345', '서울시 강남구', NOW(6))")
                .setParameter(1, userId)
                .setParameter(2, orderNumber)
                .executeUpdate();
        em.flush();
        Long orderId = ((Number) em.createNativeQuery("SELECT id FROM orders WHERE order_number = ?1")
                .setParameter(1, orderNumber)
                .getSingleResult()).longValue();
        em.getTransaction().commit();
        em.close();
        return orderId;
    }

    /** order.created envelope(JSON) 직렬화 — consumer 는 eventId/payload.orderId/userId/totalAmount 만 읽는다. */
    private String orderCreatedEnvelope(String eventId, Long orderId) {
        OrderCreatedPayload payload = new OrderCreatedPayload(
                orderId, "ORD-" + orderId, userId, 100_000L, List.of(), "홍길동", "서울시 강남구");
        try {
            return objectMapper.writeValueAsString(
                    new KafkaEventEnvelope(eventId, "order.created", LocalDateTime.now(), payload));
        } catch (Exception e) {
            throw new IllegalStateException("envelope 직렬화 실패", e);
        }
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
