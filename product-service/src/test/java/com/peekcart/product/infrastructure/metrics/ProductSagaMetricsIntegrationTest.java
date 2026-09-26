package com.peekcart.product.infrastructure.metrics;

import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.product.application.StockReservationService;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

import java.time.LocalDateTime;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * product saga 메트릭 (계획 ④-d-1 §5 P1).
 *
 * <p><b>"등록됐다" 로 통과시키지 않는다.</b> {@code meterRegistry.find(...)} 가 non-null 인지만 보면
 * 계측 지점이 틀린 곳에 있어도 통과한다 — 여기서는 <b>경로 실행 전후의 값 차이</b>를 단언한다.
 *
 * <p>멱등 no-op 에서 증가 0 인지(음성 대조)를 함께 본다. 중복 소비·CAS 패자에서도 올라가면
 * 메트릭이 실제 사건 수를 부풀리고 alert 임계값이 무의미해진다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("product saga 메트릭 통합 테스트")
class ProductSagaMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired StockReservationService reservationService;
    @Autowired EntityManagerFactory emf;
    @Autowired MeterRegistry meterRegistry;

    private Long productId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Category category = Category.create("카테고리", null);
        em.persist(category);
        em.flush();
        Product product = Product.create(category, "상품", "설명", 50_000L, null);
        em.persist(product);
        em.flush();
        em.persist(Inventory.create(product, 100));
        em.getTransaction().commit();
        em.close();
        productId = product.getId();
    }

    @Test
    @DisplayName("예약 성공 시 saga.reservation.result{outcome=success} 가 1 증가한다")
    void reservationSuccessIncrements() {
        double before = counter("saga.reservation.result", "outcome", "success");

        reservationService.reserve(nextOrderId(), UUID.randomUUID().toString(), items(2));

        assertThat(counter("saga.reservation.result", "outcome", "success")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("재고 부족이면 failure 만 증가하고 success 는 그대로다 — 음성 대조")
    void reservationFailureIncrementsOnlyFailure() {
        double successBefore = counter("saga.reservation.result", "outcome", "success");
        double failureBefore = counter("saga.reservation.result", "outcome", "failure");

        reservationService.reserve(nextOrderId(), UUID.randomUUID().toString(), items(999));

        assertThat(counter("saga.reservation.result", "outcome", "failure")).isEqualTo(failureBefore + 1);
        assertThat(counter("saga.reservation.result", "outcome", "success")).isEqualTo(successBefore);
    }

    @Test
    @DisplayName("중복 예약(멱등 no-op)은 어느 쪽도 증가시키지 않는다")
    void duplicateReserveDoesNotIncrement() {
        Long orderId = nextOrderId();
        reservationService.reserve(orderId, UUID.randomUUID().toString(), items(1));

        double successBefore = counter("saga.reservation.result", "outcome", "success");
        double failureBefore = counter("saga.reservation.result", "outcome", "failure");

        reservationService.reserve(orderId, UUID.randomUUID().toString(), items(1));

        assertThat(counter("saga.reservation.result", "outcome", "success")).isEqualTo(successBefore);
        assertThat(counter("saga.reservation.result", "outcome", "failure")).isEqualTo(failureBefore);
    }

    @Test
    @DisplayName("확정 CAS 성공 시 1 증가, 중복 확정은 증가 0 — 멱등 경로 음성 대조")
    void confirmIncrementsOnlyOnTransition() {
        Long orderId = nextOrderId();
        reservationService.reserve(orderId, UUID.randomUUID().toString(), items(1));

        double before = counter("saga.reservation.confirmed");
        reservationService.confirm(orderId);
        assertThat(counter("saga.reservation.confirmed")).isEqualTo(before + 1);

        // 중복 payment.completed → 멱등 no-op
        reservationService.confirm(orderId);
        assertThat(counter("saga.reservation.confirmed")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("복구 CAS 성공 시 1 증가, double-release 는 증가 0")
    void releaseIncrementsOnlyOnTransition() {
        Long orderId = nextOrderId();
        reservationService.reserve(orderId, UUID.randomUUID().toString(), items(1));

        double before = counter("saga.reservation.released");
        reservationService.release(orderId);
        assertThat(counter("saga.reservation.released")).isEqualTo(before + 1);

        reservationService.release(orderId);
        assertThat(counter("saga.reservation.released")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("sweeper 회수 0건이면 증가 0 — 실행당 1 이 아니다")
    void sweeperDoesNotIncrementWhenNothingReclaimed() {
        double before = counter("saga.reservation.sweeper.reclaimed");

        int reclaimed = reservationService.sweepExpiredLeases();

        assertThat(reclaimed).isZero();
        assertThat(counter("saga.reservation.sweeper.reclaimed")).isEqualTo(before);
    }

    @Test
    @DisplayName("sweeper 가 3건 회수하면 +3 — 실행당 1 이 아니라 건수만큼이다")
    void sweeperIncrementsByReclaimedCount() {
        // 만료 + 유예(5분)를 넘긴 RESERVED 3건. 회수 대상이 되려면 expires_at < now - grace 여야 한다.
        seedExpiredReserved(3);

        double before = counter("saga.reservation.sweeper.reclaimed");
        int reclaimed = reservationService.sweepExpiredLeases();

        assertThat(reclaimed).isEqualTo(3);
        assertThat(counter("saga.reservation.sweeper.reclaimed")).isEqualTo(before + 3);
    }

    @Test
    @DisplayName("보상 감지는 marker CAS 성공분만 +1, 중복은 +0 — 멱등 no-op 음성 대조")
    void compensationDetectedCountsOnlyNewMarker() {
        // 결제 완료가 도착했는데 예약이 이미 RELEASED — PAID_BUT_UNRESERVED 보상 경로
        Long orderId = nextOrderId();
        reservationService.reserve(orderId, UUID.randomUUID().toString(), items(1));
        reservationService.release(orderId);

        double before = counter("saga.compensation.detected");
        reservationService.confirm(orderId);
        assertThat(counter("saga.compensation.detected")).isEqualTo(before + 1);

        // 중복 payment.completed → marker 는 이미 있으므로 CAS 0건 → 증가 없음
        reservationService.confirm(orderId);
        assertThat(counter("saga.compensation.detected")).isEqualTo(before + 1);
    }

    // ---------- helpers ----------

    private static long orderIdSeq = 9_000L;

    private Long nextOrderId() {
        return ++orderIdSeq;
    }

    private List<ReservedItemPayload> items(int quantity) {
        return List.of(new ReservedItemPayload(productId, quantity));
    }

    /** 만료 + 유예를 넘긴 RESERVED 예약을 n건 심는다 (sweeper 회수 대상). */
    private void seedExpiredReserved(int count) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        for (int i = 0; i < count; i++) {
            em.createNativeQuery("INSERT INTO stock_reservations "
                            + "(order_id, status, items, reserved_at, expires_at, created_at) "
                            + "VALUES (?1, 'RESERVED', ?2, ?3, ?4, ?5)")
                    .setParameter(1, nextOrderId())
                    .setParameter(2, "[{\"productId\":" + productId + ",\"quantity\":1}]")
                    .setParameter(3, LocalDateTime.now().minusMinutes(60))
                    .setParameter(4, LocalDateTime.now().minusMinutes(30))
                    .setParameter(5, LocalDateTime.now().minusMinutes(60))
                    .executeUpdate();
        }
        em.getTransaction().commit();
        em.close();
    }

    private double counter(String name, String... tags) {
        Search search = meterRegistry.find(name);
        for (int i = 0; i + 1 < tags.length; i += 2) {
            search = search.tag(tags[i], tags[i + 1]);
        }
        return search.counter() == null ? 0.0 : search.counter().count();
    }
}
