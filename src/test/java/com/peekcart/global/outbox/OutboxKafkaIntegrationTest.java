package com.peekcart.global.outbox;

import com.peekcart.global.kafka.KafkaTraceHeaders;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.infrastructure.outbox.OrderOutboxEventPublisher;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.outbox.PaymentOutboxEventPublisher;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = "spring.task.scheduling.pool.size=1")
@Import({IntegrationTestConfig.class, OutboxKafkaIntegrationTest.TraceCaptureConfig.class})
@DisplayName("Outbox → Kafka E2E 통합 테스트")
class OutboxKafkaIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class TraceCaptureConfig {
        @Bean
        OrderCancelledHeaderCapture orderCancelledHeaderCapture() {
            return new OrderCancelledHeaderCapture();
        }
    }

    static class OrderCancelledHeaderCapture {
        final BlockingQueue<ConsumerRecord<String, String>> records = new LinkedBlockingQueue<>();

        @KafkaListener(topics = "order.cancelled", groupId = "test-trace-header-verifier")
        public void capture(ConsumerRecord<String, String> record) {
            records.add(record);
        }
    }

    private Long userId;

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
    @Autowired PaymentOutboxEventPublisher paymentOutboxEventPublisher;
    @Autowired OutboxPollingService outboxPollingService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired OrderCancelledHeaderCapture headerCapture;

    private Long productId;

    @BeforeEach
    void setUp() {
        MDC.clear();
        headerCapture.records.clear();
        cleanDatabase();

        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        // [PR2b] User 도메인 peel → root 는 users 행을 native insert 로 시드(FK fk_orders_user 충족, root-observable)
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

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("order.created → Outbox 저장 → Kafka 발행 → Payment 생성 (알림은 notification-service 소비)")
    void orderCreated_e2e() {
        // given
        Order order = persistOrder(OrderStatus.PENDING);

        // when: Outbox에 이벤트 저장
        orderOutboxEventPublisher.publishOrderCreated(order);
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(100);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        // when: 스케줄러 수동 호출 → Kafka 발행
        outboxPollingService.pollAndPublish();

        // then: OutboxEvent PUBLISHED 전이
        List<OutboxEvent> afterPublish = outboxEventRepository.findPendingEvents(100);
        assertThat(afterPublish).isEmpty();

        // then: 루트 Consumer 처리 대기 → Payment(PENDING) 생성 (알림 생성은 notification-service 책임)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Optional<Payment> payment = paymentRepository.findByOrderId(order.getId());
            assertThat(payment).isPresent();
            assertThat(payment.get().getStatus().name()).isEqualTo("PENDING");
        });
    }

    @Test
    @DisplayName("payment.completed → 주문 상태 PAYMENT_COMPLETED (알림은 notification-service 소비)")
    void paymentCompleted_e2e() {
        // given
        Order order = persistOrder(OrderStatus.PAYMENT_REQUESTED);
        Payment payment = persistPayment(order.getId());

        // when
        paymentOutboxEventPublisher.publishPaymentCompleted(payment, userId);
        outboxPollingService.pollAndPublish();

        // then
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            EntityManager em = emf.createEntityManager();
            try {
                Order updated = em.find(Order.class, order.getId());
                assertThat(updated.getStatus()).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
            } finally {
                em.close();
            }
        });
    }

    @Test
    @DisplayName("payment.failed → 주문 취소 (재고 복구는 Product release saga 로 이관, ADR-0012 D3)")
    void paymentFailed_e2e() {
        // given: 주문(PAYMENT_REQUESTED). 재고 복구는 더 이상 Order 동기 책임이 아니다
        //        (Product 가 payment.failed 를 소비해 예약 원장 기반 release — StockReservationSagaIntegrationTest 검증).
        Order order = persistOrderWithQuantity(2);
        Payment payment = persistPayment(order.getId());

        // when
        paymentOutboxEventPublisher.publishPaymentFailed(payment, userId);
        outboxPollingService.pollAndPublish();

        // then: root-observable 효과 = 주문 취소
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            EntityManager em = emf.createEntityManager();
            try {
                Order updated = em.find(Order.class, order.getId());
                assertThat(updated.getStatus()).isEqualTo(OrderStatus.CANCELLED);
            } finally {
                em.close();
            }
        });
    }

    @Test
    @DisplayName("order.cancelled → Kafka 발행 (루트는 미소비, 알림은 notification-service) + Payment 미생성")
    void orderCancelled_e2e() {
        // given: 실제 플로우처럼 주문 취소 후 이벤트 발행
        Order order = persistOrder(OrderStatus.PENDING);
        cancelOrder(order.getId());
        String aggregateKey = order.getId().toString();

        // when
        orderOutboxEventPublisher.publishOrderCancelled(order);
        pollUntilPublished();

        // then: 이벤트가 Kafka 로 발행됨 (테스트 전용 listener 로 확인) — 루트엔 order.cancelled consumer 없음
        ConsumerRecord<String, String> record = awaitRecordWithKey(aggregateKey);
        assertThat(record.key()).isEqualTo(aggregateKey);

        // Payment가 생성되지 않았는지 확인 (루트 결제 consumer 는 order.cancelled 미소비)
        Optional<Payment> payment = paymentRepository.findByOrderId(order.getId());
        assertThat(payment).isEmpty();
    }

    @Test
    @DisplayName("PUBLISHED 이벤트는 재폴링 시 중복 발행되지 않는다")
    void publishedEvent_notRePublished() {
        // given
        Order order = persistOrder(OrderStatus.PENDING);
        String aggregateKey = order.getId().toString();
        orderOutboxEventPublisher.publishOrderCancelled(order);
        pollUntilPublished();

        // 1회 발행되어 테스트 listener 에 도달
        awaitRecordWithKey(aggregateKey);
        assertThat(countRecordsWithKey(aggregateKey)).isEqualTo(1);

        // when: 재폴링
        outboxPollingService.pollAndPublish();

        // then: PENDING 이벤트 없음 + 해당 key 발행 record 수 변화 없음 (중복 발행 금지)
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(100);
        assertThat(pending).isEmpty();
        // 재폴링 후에도 잠시 대기하여 중복 발행이 없음을 확인
        await().during(2, TimeUnit.SECONDS).atMost(4, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countRecordsWithKey(aggregateKey)).isEqualTo(1));
    }

    @Test
    @DisplayName("MDC traceId/userId set → ProducerRecord 헤더 X-Trace-Id / X-User-Id 전파 (D-010)")
    void traceHeaders_propagated_when_mdc_set() {
        // given
        MDC.put("traceId", "test-trace-001");
        MDC.put("userId", "42");
        Order order = persistOrder(OrderStatus.PENDING);
        cancelOrder(order.getId());
        String aggregateKey = order.getId().toString();

        // when
        orderOutboxEventPublisher.publishOrderCancelled(order);
        outboxPollingService.pollAndPublish();

        // then: 별도 consumer group 의 @KafkaListener 큐에서 본 테스트가 발행한 메시지(key=orderId)만 식별해 검증
        ConsumerRecord<String, String> record = awaitRecordWithKey(aggregateKey);
        assertThat(headerValue(record, KafkaTraceHeaders.TRACE_ID)).isEqualTo("test-trace-001");
        assertThat(headerValue(record, KafkaTraceHeaders.USER_ID)).isEqualTo("42");
    }

    @Test
    @DisplayName("MDC 미설정 → ProducerRecord 헤더 X-Trace-Id / X-User-Id 미주입 (빈 헤더 생성 금지)")
    void traceHeaders_absent_when_mdc_clear() {
        // given (MDC.clear 는 setUp 에서 보장)
        Order order = persistOrder(OrderStatus.PENDING);
        cancelOrder(order.getId());
        String aggregateKey = order.getId().toString();

        // when
        orderOutboxEventPublisher.publishOrderCancelled(order);
        outboxPollingService.pollAndPublish();

        // then
        ConsumerRecord<String, String> record = awaitRecordWithKey(aggregateKey);
        assertThat(record.headers().lastHeader(KafkaTraceHeaders.TRACE_ID)).isNull();
        assertThat(record.headers().lastHeader(KafkaTraceHeaders.USER_ID)).isNull();
    }

    // ── 헬퍼 메서드 ──

    /**
     * Outbox 이벤트가 PUBLISHED 로 전이될 때까지 폴링을 반복한다.
     *
     * <p>프로덕션 스케줄러는 매 사이클 {@code pollAndPublish()} 를 반복 호출하므로, 콜드 스타트에서
     * 첫 발행이 producer 타임아웃({@code max.block.ms}/{@code delivery.timeout.ms}, D-013)을 초과해
     * 이벤트가 PENDING 으로 남더라도 다음 사이클에 재발행되어 자가치유된다. 단발 호출만 하던 테스트는
     * 이 재시도 의미가 빠져 CI 콜드 스타트에서 간헐 실패했다(D-019). PENDING 이 비워질 때까지 재폴링해
     * 프로덕션과 동일한 발행 보장을 부여한다.</p>
     */
    private void pollUntilPublished() {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            outboxPollingService.pollAndPublish();
            assertThat(outboxEventRepository.findPendingEvents(100)).isEmpty();
        });
    }

    /**
     * headerCapture 큐에서 주어진 key 와 일치하는 record 를 찾을 때까지 polling.
     * 같은 클래스의 다른 테스트가 발행한 stale record 가 큐에 섞여 있을 수 있으므로
     * key (= order aggregateId) 로 현재 테스트의 record 를 식별한다.
     */
    private ConsumerRecord<String, String> awaitRecordWithKey(String key) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : headerCapture.records) {
                if (key.equals(r.key())) {
                    return r;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting record", e);
            }
        }
        throw new AssertionError("no record with key=" + key + " arrived within timeout");
    }

    /** headerCapture 큐에서 주어진 key 와 일치하는 record 수를 센다 (중복 발행 검증용). */
    private long countRecordsWithKey(String key) {
        return headerCapture.records.stream().filter(r -> key.equals(r.key())).count();
    }

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private Order persistOrder(OrderStatus targetStatus) {
        return persistOrderWithQuantity(targetStatus, 2);
    }

    private Order persistOrderWithQuantity(int quantity) {
        return persistOrderWithQuantity(OrderStatus.PAYMENT_REQUESTED, quantity);
    }

    private Order persistOrderWithQuantity(OrderStatus targetStatus, int quantity) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        List<OrderItemData> items = List.of(new OrderItemData(productId, quantity, 50_000L));
        Order order = Order.create(userId, "ORD-TEST-" + System.nanoTime(),
                "홍길동", "010-1234-5678", "12345", "서울시 강남구", items);

        if (targetStatus == OrderStatus.PAYMENT_REQUESTED) {
            order.transitionTo(OrderStatus.PAYMENT_REQUESTED);
        }

        em.persist(order);
        em.getTransaction().commit();
        em.close();
        return order;
    }

    private void cancelOrder(Long orderId) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Order order = em.find(Order.class, orderId);
        order.cancel();
        em.getTransaction().commit();
        em.close();
    }

    private Payment persistPayment(Long orderId) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        Payment payment = Payment.create(orderId, 100_000L);
        em.persist(payment);
        em.getTransaction().commit();
        em.close();
        return payment;
    }
}
