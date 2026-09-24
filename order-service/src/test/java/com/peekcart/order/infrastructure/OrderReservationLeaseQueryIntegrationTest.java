package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예약 lease 만료 조회 통합 테스트 (계획 P3/P4 · §5).
 *
 * <p>이 조회가 담당하는 구간은 기존 두 만료 조회가 <b>비우지 못하는</b> 곳이다 —
 * {@code findUnconfirmedReservationBefore} 는 {@code reservationConfirmedAt IS NULL} 만,
 * {@code findExpiredPaymentRequested} 는 {@code PAYMENT_REQUESTED} 만 잡는다. 그 사이의
 * "예약 확정 완료 + 결제 미시작 PENDING" 은 수명 상한이 없어 Product sweeper 가 살아있는
 * 주문의 재고를 회수하는 oversell 의 원인이었다(계획 §2.3-A).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("예약 lease 만료 조회 통합 테스트")
class OrderReservationLeaseQueryIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrderRepository orderRepository;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("lease 가 만료된 PENDING 주문만 잡고, 남아있는 주문은 제외한다")
    void expiredLeaseOnly() {
        Long expired = seed(OrderStatus.PENDING, LocalDateTime.now().minusMinutes(1));
        Long alive = seed(OrderStatus.PENDING, LocalDateTime.now().plusMinutes(30));

        List<Order> result = orderRepository.findExpiredReservationLease(LocalDateTime.now());

        assertThat(ids(result)).contains(expired).doesNotContain(alive);
    }

    @Test
    @DisplayName("lease 미수신(null)은 만료 판정 대상이 아니다 (구 메시지 하위 호환)")
    void nullLease_excluded() {
        Long noLease = seed(OrderStatus.PENDING, null);

        List<Order> result = orderRepository.findExpiredReservationLease(LocalDateTime.now());

        assertThat(ids(result)).doesNotContain(noLease);
    }

    @Test
    @DisplayName("이미 결제로 넘어간 주문은 lease 가 만료돼도 잡지 않는다 (결제 타임아웃 잡의 소관)")
    void paymentRequested_excluded() {
        Long paying = seed(OrderStatus.PAYMENT_REQUESTED, LocalDateTime.now().minusMinutes(1));

        List<Order> result = orderRepository.findExpiredReservationLease(LocalDateTime.now());

        assertThat(ids(result)).doesNotContain(paying);
    }

    @Test
    @DisplayName("기존 두 만료 조회는 이 구간을 비우지 못한다 (갭의 존재 증명)")
    void existingQueriesDoNotCoverThisGap() {
        Long gap = seed(OrderStatus.PENDING, LocalDateTime.now().minusMinutes(1));

        List<Order> byUnconfirmed =
                orderRepository.findUnconfirmedReservationBefore(LocalDateTime.now().minusMinutes(5));
        List<Order> byPaymentTimeout =
                orderRepository.findExpiredPaymentRequested(LocalDateTime.now().minusMinutes(15));

        assertThat(ids(byUnconfirmed)).doesNotContain(gap);
        assertThat(ids(byPaymentTimeout)).doesNotContain(gap);
        assertThat(ids(orderRepository.findExpiredReservationLease(LocalDateTime.now()))).contains(gap);
    }

    private List<Long> ids(List<Order> orders) {
        return orders.stream().map(Order::getId).toList();
    }

    /** 예약이 확정된(=reservationConfirmedAt 채워진) 주문을 시드한다. */
    private Long seed(OrderStatus status, LocalDateTime reservationExpiresAt) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Order order = Order.create(42L, "ORD-" + UUID.randomUUID(), "받는이", "01000000000", "12345", "주소",
                List.of(new OrderItemData(100L, 1, 1_000L)));
        em.persist(order);
        em.flush();
        Long id = order.getId();
        em.createNativeQuery("UPDATE orders SET status = ?1, reservation_confirmed_at = ?2, "
                        + "reservation_expires_at = ?3 WHERE id = ?4")
                .setParameter(1, status.name())
                .setParameter(2, LocalDateTime.now().minusMinutes(30))
                .setParameter(3, reservationExpiresAt)
                .setParameter(4, id)
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
        return id;
    }
}
