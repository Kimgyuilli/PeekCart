package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.product.domain.model.Category;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code findExpiredPaymentRequested} JPQL 통합 테스트 (strangler-3, P6).
 * 실 DB 에서 paymentRequestedAt 기준 + null 폴백(마이그레이션 직후 backfill 미적용 행 안전) 을 검증한다.
 * mock 으로는 잡히지 않는 JPQL 조건/JOIN FETCH 회귀를 막는다.
 */
@SpringBootTest
@Testcontainers
@Import(IntegrationTestConfig.class)
@DisplayName("findExpiredPaymentRequested 쿼리 통합 테스트")
class OrderExpiredPaymentRequestedQueryIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired OrderRepository orderRepository;

    private static final LocalDateTime PAST = LocalDateTime.now().minusMinutes(20);
    private static final LocalDateTime RECENT = LocalDateTime.now().minusMinutes(5);

    private Long userId;
    private Long productId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        // category + product 시드 (order_items FK fk_order_items_product 충족)
        Category category = Category.create("카테고리", null);
        em.persist(category);
        em.flush();
        Product product = Product.create(category, "상품", "설명", 1_000L, null);
        em.persist(product);
        em.flush();
        productId = product.getId();
        // User 도메인 peel → root 는 users 행을 native insert 로 시드(orders FK fk_orders_user 충족)
        em.createNativeQuery(
                "INSERT INTO users (email, password_hash, name, role, created_at, updated_at) "
                        + "VALUES ('timeout@peekcart.com', 'hashed-pw', '타임아웃유저', 'USER', NOW(6), NOW(6))")
                .executeUpdate();
        userId = ((Number) em.createNativeQuery("SELECT id FROM users WHERE email = 'timeout@peekcart.com'")
                .getSingleResult()).longValue();
        em.getTransaction().commit();
        em.close();
    }

    @Test
    @DisplayName("paymentRequestedAt 이 cutoff 이전이면 만료로 잡고, 이후면 제외한다")
    void expiredByPaymentRequestedAt() {
        Long expired = seed(OrderStatus.PAYMENT_REQUESTED, PAST, PAST);
        Long recent = seed(OrderStatus.PAYMENT_REQUESTED, PAST, RECENT);

        List<Order> result = orderRepository.findExpiredPaymentRequested(cutoff());

        assertThat(ids(result)).contains(expired).doesNotContain(recent);
    }

    @Test
    @DisplayName("null 폴백: paymentRequestedAt 이 null 이면 orderedAt 으로 만료 판정 (backfill 미적용 행 안전)")
    void nullPaymentRequestedAt_fallsBackToOrderedAt() {
        Long nullPastOrdered = seed(OrderStatus.PAYMENT_REQUESTED, PAST, null);
        Long nullRecentOrdered = seed(OrderStatus.PAYMENT_REQUESTED, RECENT, null);

        List<Order> result = orderRepository.findExpiredPaymentRequested(cutoff());

        assertThat(ids(result)).contains(nullPastOrdered).doesNotContain(nullRecentOrdered);
    }

    @Test
    @DisplayName("PAYMENT_REQUESTED 가 아닌 주문은 오래돼도 잡지 않는다")
    void nonPaymentRequested_excluded() {
        Long pending = seed(OrderStatus.PENDING, PAST, null);

        List<Order> result = orderRepository.findExpiredPaymentRequested(cutoff());

        assertThat(ids(result)).doesNotContain(pending);
    }

    private LocalDateTime cutoff() {
        return LocalDateTime.now().minusMinutes(15);
    }

    private List<Long> ids(List<Order> orders) {
        return orders.stream().map(Order::getId).toList();
    }

    private Long seed(OrderStatus status, LocalDateTime orderedAt, LocalDateTime paymentRequestedAt) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Order order = Order.create(userId, "ORD-" + UUID.randomUUID(), "받는이", "01000000000", "12345", "주소",
                List.of(new OrderItemData(productId, 1, 1_000L)));
        em.persist(order);
        em.flush();
        Long id = order.getId();
        em.createNativeQuery("UPDATE orders SET status = ?1, ordered_at = ?2, payment_requested_at = ?3 WHERE id = ?4")
                .setParameter(1, status.name())
                .setParameter(2, orderedAt)
                .setParameter(3, paymentRequestedAt)
                .setParameter(4, id)
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
        return id;
    }
}
