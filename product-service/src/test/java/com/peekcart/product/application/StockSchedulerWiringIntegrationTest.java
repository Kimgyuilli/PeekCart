package com.peekcart.product.application;

import com.peekcart.product.domain.model.ReservationStatus;
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
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Product 예약 lease sweeper 의 <b>스케줄러 배선</b> 계약 (계획 P17 · V20).
 *
 * <p>{@code StockReservationLeaseSweepTest} 는 sweeper 객체의 메서드를 직접 호출한다 — 회수 규칙은
 * 검증하지만 {@code @Scheduled} 를 지워도 통과한다. sweeper 는 <b>안전망</b>이라 평소 회수 건수가
 * 0이고, 그래서 배선이 끊겨도 지표상 아무 일도 일어나지 않는다. 조용히 죽기 가장 쉬운 잡이다.
 *
 * <p>여기서는 직접 호출 없이 <b>실제 발화</b>로 원장이 회수되는 것까지 본다.
 * 결정성 근거는 {@code OrderSchedulerWiringIntegrationTest} 와 같다 — 주기를 짧게, lock 하한을 0으로
 * 덮어 반복 발화가 seed 를 잡게 한다. 운영 기본값은
 * {@link StockSchedulerPropertiesTest} 가 따로 고정한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "app.scheduler.stock.lease-sweep-delay=200ms",
        "app.scheduler.stock.lock-at-least-for=0s",
        "app.scheduler.stock.lock-at-most-for=30s",
        // 회수 대상 판정을 좁혀 seed 가 즉시 대상이 되게 한다(회수 '규칙' 은 단위 테스트 소관)
        "app.reservation.lease.sweeper-grace=1s",
        // ADR-0029: 스케줄러는 테스트에서 기본 off 다. 직접 호출 없이 @Scheduled 가 발화하는 것이 검증 대상이다.
        "app.scheduling.enabled=true"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
// 켠 자율 writer 의 수명을 자기 클래스에 가둔다 (ADR-0029 D3) — context 가 캐시된 채 남으면
// 스케줄러가 이후 클래스의 공유 브로커·DB 를 계속 고친다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Product 스케줄러 배선 계약")
class StockSchedulerWiringIntegrationTest extends AbstractIntegrationTest {

    private Long productId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        productId = seedProductWithInventory();
    }

    @Test
    @DisplayName("[SAGA-SCHEDULER-WIRING-PRODUCT] 만료 예약이 직접 호출 없이 회수된다 — @Scheduled 가 실제로 발화한다")
    void expiredLeaseIsReclaimedByActualScheduling() {
        Long orderId = seed(ReservationStatus.RESERVED, LocalDateTime.now().minusMinutes(10));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(statusOf(orderId)).isEqualTo(ReservationStatus.RELEASED.name()));
    }

    /**
     * 앞 검사가 "무언가가 전부 RELEASED 로 만들었다" 로 통과한 게 아님을 고정한다.
     */
    @Test
    @DisplayName("[SAGA-SCHEDULER-WIRING-PRODUCT-NEGATIVE] 만료되지 않은 예약은 sweeper 가 건드리지 않는다")
    void unexpiredLeaseIsUntouched() {
        Long expired = seed(ReservationStatus.RESERVED, LocalDateTime.now().minusMinutes(10));
        Long future = seed(ReservationStatus.RESERVED, LocalDateTime.now().plusMinutes(30));

        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(statusOf(expired)).isEqualTo(ReservationStatus.RELEASED.name()));

        assertThat(statusOf(future)).isEqualTo(ReservationStatus.RESERVED.name());
    }

    /**
     * 회수는 재고 복구까지 한 트랜잭션이다 — {@code inventories} 행이 없으면
     * {@code restoreStock} 이 실패해 <b>RELEASED 전이까지 롤백</b>된다(그래서 상태가 RESERVED 로
     * 남는다). 배선을 보려면 회수가 끝까지 성공할 수 있는 상태를 만들어 줘야 한다.
     */
    private Long seedProductWithInventory() {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery("INSERT INTO categories (name) VALUES ('테스트')")
                    .executeUpdate();
            Number categoryId = (Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult();
            em.createNativeQuery("INSERT INTO products "
                            + "(category_id, name, price, status, created_at, version) "
                            + "VALUES (?1, '상품', 10000, 'ON_SALE', NOW(6), 0)")
                    .setParameter(1, categoryId.longValue())
                    .executeUpdate();
            Number pid = (Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult();
            em.createNativeQuery("INSERT INTO inventories (product_id, stock, version, updated_at) "
                            + "VALUES (?1, 100, 0, NOW(6))")
                    .setParameter(1, pid.longValue())
                    .executeUpdate();
            em.getTransaction().commit();
            return pid.longValue();
        } finally {
            em.close();
        }
    }

    private long nextOrderId = 7000L;

    private Long seed(ReservationStatus status, LocalDateTime expiresAt) {
        Long orderId = nextOrderId++;
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.createNativeQuery("INSERT INTO stock_reservations "
                        + "(order_id, status, items, reserved_at, expires_at, created_at) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
                .setParameter(1, orderId)
                .setParameter(2, status.name())
                .setParameter(3, "[{\"productId\":" + productId + ",\"quantity\":1}]")
                .setParameter(4, LocalDateTime.now().minusMinutes(40))
                .setParameter(5, expiresAt)
                .setParameter(6, LocalDateTime.now().minusMinutes(40))
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
        return orderId;
    }

    private String statusOf(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return (String) em.createNativeQuery(
                            "SELECT status FROM stock_reservations WHERE order_id = ?1")
                    .setParameter(1, orderId)
                    .getSingleResult();
        } finally {
            em.close();
        }
    }
}
