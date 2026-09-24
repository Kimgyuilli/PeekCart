package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.RollbackException;
import org.hibernate.StaleObjectStateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L-013 게이트 — 주문 상태 전이 동시성 실측 (계획 §3 P1, ADR-0012 D3/D4).
 *
 * <p>{@code payment.completed} 소비(→{@code PAYMENT_COMPLETED})와 {@link
 * com.peekcart.order.infrastructure.scheduler.OrderTimeoutScheduler} 취소(→{@code CANCELLED})가
 * 같은 주문에 동시 적용되는 상황을 <b>결정적으로</b> 재현한다. 두 EntityManager 가 커밋 전에 모두
 * 같은 스냅샷(같은 {@code version})을 읽도록 강제하므로 스케줄링 운에 의존하지 않는다 —
 * 확률적 미재현을 L-013 기각 근거로 쓸 수 없게 하기 위함이다(계획 §2.3-D).
 *
 * <p><b>실측 결과(@Version 도입 전)</b>: 두 순서 모두 lost update 가 발생해 나중 커밋이 앞 커밋을
 * 덮었다. 취소 선커밋 → 최종 {@code PAYMENT_COMPLETED}(취소 유실), 결제 선커밋 → 최종
 * {@code CANCELLED}(과금된 주문이 취소로 표시). 상태 전이 규칙({@code OrderStatus.canTransitionTo})
 * 은 각 트랜잭션의 <i>스냅샷</i> 기준으로만 평가되므로 전이 가드로는 막히지 않는다.
 * → L-013 <b>양성</b>, P2({@code @Version}) 승격.
 *
 * <p>본 테스트는 {@code @Version} 도입 후의 수렴을 고정한다: 진 쪽 커밋이 조용히 덮는 대신
 * {@link OptimisticLockException} 으로 드러난다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("L-013 주문 상태 전이 동시성 (결정적 재현)")
class OrderStateTransitionRaceIntegrationTest extends AbstractIntegrationTest {

    private final Long userId = 42L;
    private final Long productId = 100L;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("취소 선커밋 → 결제 완료 커밋은 낙관 락으로 거부된다 (취소 유실 차단)")
    void cancelCommitsFirst_paymentCompletedIsRejected() {
        Long orderId = seedPaymentRequested();

        EntityManager cancelTx = emf.createEntityManager();
        EntityManager paymentTx = emf.createEntityManager();
        try {
            // 두 트랜잭션이 같은 스냅샷(같은 version)을 읽도록 강제 — 여기가 race 의 본질이다.
            cancelTx.getTransaction().begin();
            paymentTx.getTransaction().begin();
            Order byCancel = cancelTx.find(Order.class, orderId);
            Order byPayment = paymentTx.find(Order.class, orderId);
            assertThat(byCancel.getVersion()).isEqualTo(byPayment.getVersion());

            byCancel.cancel();
            cancelTx.getTransaction().commit();

            byPayment.transitionTo(OrderStatus.PAYMENT_COMPLETED);
            assertThatThrownBy(() -> paymentTx.getTransaction().commit())
                    .isInstanceOf(RollbackException.class)
                    .hasCauseInstanceOf(OptimisticLockException.class)
                    .hasRootCauseInstanceOf(StaleObjectStateException.class);
        } finally {
            closeQuietly(cancelTx);
            closeQuietly(paymentTx);
        }

        assertThat(currentStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
    }

    @Test
    @DisplayName("결제 완료 선커밋 → 취소 커밋은 낙관 락으로 거부된다 (과금 후 취소 표시 차단)")
    void paymentCompletedCommitsFirst_cancelIsRejected() {
        Long orderId = seedPaymentRequested();

        EntityManager paymentTx = emf.createEntityManager();
        EntityManager cancelTx = emf.createEntityManager();
        try {
            paymentTx.getTransaction().begin();
            cancelTx.getTransaction().begin();
            Order byPayment = paymentTx.find(Order.class, orderId);
            Order byCancel = cancelTx.find(Order.class, orderId);
            assertThat(byPayment.getVersion()).isEqualTo(byCancel.getVersion());

            byPayment.transitionTo(OrderStatus.PAYMENT_COMPLETED);
            paymentTx.getTransaction().commit();

            byCancel.cancel();
            assertThatThrownBy(() -> cancelTx.getTransaction().commit())
                    .isInstanceOf(RollbackException.class)
                    .hasCauseInstanceOf(OptimisticLockException.class)
                    .hasRootCauseInstanceOf(StaleObjectStateException.class);
        } finally {
            closeQuietly(paymentTx);
            closeQuietly(cancelTx);
        }

        assertThat(currentStatus(orderId)).isEqualTo(OrderStatus.PAYMENT_COMPLETED);
    }

    private OrderStatus currentStatus(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.find(Order.class, orderId).getStatus();
        } finally {
            em.close();
        }
    }

    private void closeQuietly(EntityManager em) {
        if (em.getTransaction().isActive()) {
            em.getTransaction().rollback();
        }
        em.close();
    }

    /** 결제 요청까지 진행된(예약 확정 완료) 주문을 시드한다. */
    private Long seedPaymentRequested() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Order order = Order.create(userId, "ORD-" + UUID.randomUUID(), "받는이", "01000000000", "12345", "주소",
                List.of(new OrderItemData(productId, 1, 1_000L)));
        em.persist(order);
        em.flush();
        Long id = order.getId();
        em.createNativeQuery("UPDATE orders SET status = ?1, reservation_confirmed_at = ?2, payment_requested_at = ?3 "
                        + "WHERE id = ?4")
                .setParameter(1, OrderStatus.PAYMENT_REQUESTED.name())
                .setParameter(2, LocalDateTime.now().minusMinutes(3))
                .setParameter(3, LocalDateTime.now().minusMinutes(2))
                .setParameter(4, id)
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
        return id;
    }
}
