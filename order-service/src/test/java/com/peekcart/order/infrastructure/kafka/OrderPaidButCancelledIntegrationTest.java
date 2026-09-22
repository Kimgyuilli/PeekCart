package com.peekcart.order.infrastructure.kafka;

import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.order.domain.model.CompensationReason;
import com.peekcart.order.domain.model.CompensationStatus;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderCompensation;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderCompensationRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 취소된 주문에 결제 완료가 도착하는 경로의 종결 검증 (GW-2 #2·#5).
 *
 * <p>낙관 락 경쟁에서 취소가 이기면 결제 완료 소비는 {@code CANCELLED} 를 읽게 된다. 이때
 * (1) {@code ORD-003} 으로 던져 DLQ 로 가지 않고 (2) <b>영속</b> 보상 원장이 남으며
 * (3) 재소비(DLQ 재발행 등)에도 원장이 1행이어야 한다. 알림만으로는 종결로 치지 않는다 —
 * order-service 의 {@code SlackPort} 는 배포 구성상 no-op 이기 때문이다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("PAID_BUT_CANCELLED 보상 원장 통합 테스트")
class OrderPaidButCancelledIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrderEventConsumer consumer;
    @Autowired OrderCompensationRepository compensationRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("취소된 주문에 payment.completed 도착 → 예외 없이 보상 원장 OPEN 1행")
    void cancelledOrder_paymentCompleted_recordsOpenCompensation() {
        Long orderId = seedCancelledOrder();
        // MeterRegistry 는 컨텍스트 공유라 테스트 간 누적된다 → 델타로 본다.
        double before = compensationDetectedCount();

        assertThatCode(() -> consumer.handlePaymentCompleted(paymentCompleted(orderId, UUID.randomUUID().toString())))
                .doesNotThrowAnyException();

        Optional<OrderCompensation> found =
                compensationRepository.findByOrderIdAndReason(orderId, CompensationReason.PAID_BUT_CANCELLED);
        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(CompensationStatus.OPEN);
        assertThat(currentStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);
        // 계측 배선 검증 (구현 ④-d-1) — 이 단언이 없으면 consumer 에서 계측 호출을 지워도 통과한다.
        assertThat(compensationDetectedCount()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("다른 eventId 로 재소비(DLQ 재발행 모사)해도 원장은 1행 — 멱등")
    void reconsumedWithNewEventId_ledgerStaysSingleRow() {
        Long orderId = seedCancelledOrder();

        double before = compensationDetectedCount();
        consumer.handlePaymentCompleted(paymentCompleted(orderId, UUID.randomUUID().toString()));
        double afterFirst = compensationDetectedCount();

        consumer.handlePaymentCompleted(paymentCompleted(orderId, UUID.randomUUID().toString()));

        assertThat(countCompensations(orderId)).isEqualTo(1L);
        // 원장이 1행이면 감지도 1건이다 — 재소비에서 올라가면 메트릭이 실제 사건 수를 부풀린다.
        assertThat(afterFirst).isEqualTo(before + 1);
        assertThat(compensationDetectedCount()).isEqualTo(before + 1);
    }

    private double compensationDetectedCount() {
        var c = meterRegistry.find("saga.compensation.detected").counter();
        return c == null ? 0.0 : c.count();
    }

    private long countCompensations(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return (Long) em.createQuery(
                            "SELECT COUNT(c) FROM OrderCompensation c WHERE c.orderId = :orderId")
                    .setParameter("orderId", orderId)
                    .getSingleResult();
        } finally {
            em.close();
        }
    }

    private OrderStatus currentStatus(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.find(Order.class, orderId).getStatus();
        } finally {
            em.close();
        }
    }

    private String paymentCompleted(Long orderId, String eventId) {
        try {
            return objectMapper.writeValueAsString(new KafkaEventEnvelope(
                    eventId, "payment.completed", LocalDateTime.now(), Map.of("orderId", orderId)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long seedCancelledOrder() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Order order = Order.create(42L, "ORD-" + UUID.randomUUID(), "받는이", "01000000000", "12345", "주소",
                List.of(new OrderItemData(100L, 1, 1_000L)));
        em.persist(order);
        em.flush();
        Long id = order.getId();
        em.createNativeQuery("UPDATE orders SET status = 'CANCELLED' WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
        return id;
    }
}
