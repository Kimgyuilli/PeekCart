package com.peekcart.order.infrastructure.scheduler;

import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Order 타임아웃 잡의 <b>스케줄러 배선</b> 계약 (계획 P17 · V20).
 *
 * <p><b>왜 필요한가.</b> 기존 {@code OrderTimeoutSchedulerTest} 는 {@code @InjectMocks} 로 만든
 * 객체의 메서드를 <b>직접 호출</b>한다. 그래서 {@code @Scheduled} 를 통째로 지워도 전부 통과한다 —
 * 잡의 로직은 검증하지만 <b>잡이 돈다는 사실</b>은 아무것도 고정하지 않는다. 운영에서 그 애노테이션이
 * 사라지면 만료 주문이 영원히 안 닫히는데 health 는 정상이라 아무도 모른다.
 *
 * <p>여기서는 <b>실제 Spring scheduling 이 발화</b>해 DB 상태가 바뀌는 것까지 본다. 직접 호출은
 * 하지 않는다 — 한 번이라도 호출하면 이 테스트도 같은 false-green 이 된다.
 *
 * <h3>결정성 (V20 · 계획 R3 #11)</h3>
 * 운영 기본값은 {@code fixedDelay=60s}, {@code lockAtLeastFor=30s} 다. 그대로 쓰면 기동 직후의
 * <b>빈 작업 선발화가 lock 을 30초 잡아</b> 뒤이어 seed 한 주문이 그 창 안에 처리되지 않는다 —
 * 대기 시간을 늘려 맞추는 건 타이밍 의존이다. 그래서 이 테스트는 주기와 lock 하한만 짧게 덮는다:
 * <ul>
 *   <li>{@code fixedDelay} 를 짧게 → 발화가 반복되므로 <b>어느 한 번</b>이 seed 를 잡는다</li>
 *   <li>{@code lockAtLeastFor=0} → 빈 실행이 lock 을 붙들지 않는다</li>
 * </ul>
 *
 * <p><b>운영 기본값 자체는 여기서 검증하지 않는다</b> — 그건 {@link OrderSchedulerPropertiesTest}
 * 소관이다. 두 관심사를 한 테스트에 넣으면 둘 중 하나는 반드시 거짓이 된다(빠른 주기로 덮으면
 * 운영값을 못 보고, 운영값을 쓰면 결정적이지 않다).
 */
@SpringBootTest
/**
 * 이 클래스는 배경 스케줄링을 켠다(D-032 기본값은 off). context 가 캐시되면 자기 테스트가
 * 끝난 뒤에도 타이머가 계속 돌면서 <b>공유 DB</b> 를 고쳐 다른 클래스의 단언을 깬다.
 * 클래스 종료 시 context 를 닫아 타이머를 멈춘다. 컨테이너는 static 이라 영향받지 않는다.
 */
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestPropertySource(properties = {
        // 타이머 발화 자체가 검증 대상이라 배경 스케줄링을 켠다 (D-032 기본값은 off).
        "app.scheduling.enabled=true",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 배선만 본다 — 운영 기본값은 OrderSchedulerPropertiesTest 가 고정한다
        "app.scheduler.order.cancel-expired-delay=200ms",
        "app.scheduler.order.unconfirmed-reservation-delay=200ms",
        "app.scheduler.order.lease-expiry-delay=200ms",
        "app.scheduler.order.lock-at-least-for=0s",
        "app.scheduler.order.lock-at-most-for=30s"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("Order 스케줄러 배선 계약")
class OrderSchedulerWiringIntegrationTest extends AbstractIntegrationTest {

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    /**
     * {@code @Scheduled} 를 지우면 이 테스트가 실패한다 — 그게 이 테스트의 전부다.
     * 잡의 <b>내용</b>(어떤 주문을 왜 취소하는가)은 단위 테스트가 이미 고정하고 있다.
     *
     * <p><b>세 잡을 각각 겨눈다.</b> 한 fixture 로 뭉치면 첫 잡의 배선만 증명된다 —
     * 나머지 둘은 {@code PENDING} 전용 조회라 {@code PAYMENT_REQUESTED} 주문을 집지 않고,
     * 그 상태로는 두 번째·세 번째 {@code @Scheduled} 를 지워도 통과한다(diff 리뷰 #3).
     */
    @Test
    @DisplayName("[SAGA-SCHEDULER-WIRING-ORDER] 세 타임아웃 잡이 직접 호출 없이 각각 발화한다 — @Scheduled 배선")
    void allThreeTimeoutJobsFireByActualScheduling() {
        // 잡1: 결제 대기 상한 초과 (PAYMENT_REQUESTED + paymentRequestedAt 과거)
        Long paymentExpired = seedExpiredPaymentRequestedOrder();
        // 잡2: 예약 미확정 PENDING (reservationConfirmedAt IS NULL + orderedAt 과거)
        Long unconfirmed = seedUnconfirmedReservationOrder();
        // 잡3: 예약 lease 만료 PENDING (reservationExpiresAt 과거)
        Long leaseExpired = seedExpiredLeaseOrder();

        // 어떤 스케줄러 메서드도 직접 호출하지 않는다. 상태가 바뀌면 발화한 것이다.
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(statusOf(paymentExpired)).isEqualTo(OrderStatus.CANCELLED.name());
            assertThat(statusOf(unconfirmed)).isEqualTo(OrderStatus.CANCELLED.name());
            assertThat(statusOf(leaseExpired)).isEqualTo(OrderStatus.CANCELLED.name());
        });
    }

    /**
     * 앞 검사가 <b>다른 이유로</b> 통과한 게 아님을 고정한다 — 대상이 아닌 주문까지 취소된다면
     * "스케줄러가 돌았다" 가 아니라 "무언가가 전부 취소했다" 이고, 그건 계약이 아니다.
     */
    @Test
    @DisplayName("[SAGA-SCHEDULER-WIRING-ORDER-NEGATIVE] 만료되지 않은 주문은 스케줄러가 건드리지 않는다")
    void freshOrderIsNotCancelled() throws Exception {
        Long expired = seedExpiredPaymentRequestedOrder();
        Long fresh = seedFreshPaymentRequestedOrder();

        // 만료분이 닫히면 스케줄러는 최소 한 바퀴를 돈 것이다 — 고정 sleep 없이 판정할 수 있다.
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(statusOf(expired)).isEqualTo(OrderStatus.CANCELLED.name()));

        assertThat(statusOf(fresh)).isEqualTo(OrderStatus.PAYMENT_REQUESTED.name());
    }

    // ---------------- fixtures ----------------

    private Long seedExpiredPaymentRequestedOrder() {
        return seedOrder(LocalDateTime.now().minusHours(2));
    }

    private Long seedFreshPaymentRequestedOrder() {
        return seedOrder(LocalDateTime.now());
    }

    /** 잡2 대상: PENDING · 예약 미확정 · 주문 시각이 cutoff(5분) 이전. */
    private Long seedUnconfirmedReservationOrder() {
        return seedPendingOrder(LocalDateTime.now().minusHours(2), null, null);
    }

    /** 잡3 대상: PENDING · lease 만료. 예약은 확정됐으므로 잡2 에는 걸리지 않는다. */
    private Long seedExpiredLeaseOrder() {
        return seedPendingOrder(LocalDateTime.now(), LocalDateTime.now().minusHours(1),
                LocalDateTime.now().minusMinutes(30));
    }

    private Long seedPendingOrder(LocalDateTime orderedAt, LocalDateTime reservationExpiresAt,
                                  LocalDateTime reservationConfirmedAt) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            Order order = Order.create(1L, "ORD-" + UUID.randomUUID(), "받는이", "01000000000",
                    "12345", "주소", List.of(new OrderItemData(1L, 1, 10_000L)));
            em.persist(order);
            em.flush();
            Long id = order.getId();
            em.createNativeQuery("UPDATE orders SET status = ?1, ordered_at = ?2, "
                            + "reservation_expires_at = ?3, reservation_confirmed_at = ?4 WHERE id = ?5")
                    .setParameter(1, OrderStatus.PENDING.name())
                    .setParameter(2, orderedAt)
                    .setParameter(3, reservationExpiresAt)
                    .setParameter(4, reservationConfirmedAt)
                    .setParameter(5, id)
                    .executeUpdate();
            em.getTransaction().commit();
            return id;
        } finally {
            em.close();
        }
    }

    /**
     * 엔티티로 만들고 상태·시각만 네이티브로 되돌린다 — {@code paymentRequestedAt} 은 도메인이
     * 전이 시점에 찍는 값이라 생성자로 과거를 넣을 수 없다.
     */
    private Long seedOrder(LocalDateTime paymentRequestedAt) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            Order order = Order.create(1L, "ORD-" + UUID.randomUUID(), "받는이", "01000000000",
                    "12345", "주소", List.of(new OrderItemData(1L, 1, 10_000L)));
            em.persist(order);
            em.flush();
            Long id = order.getId();
            em.createNativeQuery(
                            "UPDATE orders SET status = ?1, payment_requested_at = ?2 WHERE id = ?3")
                    .setParameter(1, OrderStatus.PAYMENT_REQUESTED.name())
                    .setParameter(2, paymentRequestedAt)
                    .setParameter(3, id)
                    .executeUpdate();
            em.getTransaction().commit();
            return id;
        } finally {
            em.close();
        }
    }

    private String statusOf(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return (String) em.createNativeQuery("SELECT status FROM orders WHERE id = ?1")
                    .setParameter(1, orderId)
                    .getSingleResult();
        } finally {
            em.close();
        }
    }
}
