package com.peekcart.product.application;

import com.peekcart.product.domain.model.ReservationStatus;
import com.peekcart.product.domain.model.StockReservation;
import com.peekcart.product.domain.repository.StockReservationRepository;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * lease 만료 조회의 경계 검증 (GW-2 #4 · 계획 P4 · P13).
 *
 * <p>mock 이 아니라 실 DB 로 확인하는 이유는 두 가지다 — (1) sweeper 회수 대상 판정은 인덱스가 걸린
 * JPQL 조건이라 조건식 회귀가 mock 으로는 안 잡힌다 (2) 유예(grace) 경계와 상태 필터를 동시에
 * 만족해야 "Order 취소가 먼저"라는 순서 불변식이 성립한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("예약 lease 만료 조회 통합 테스트")
class StockReservationLeaseSweepIntegrationTest extends AbstractIntegrationTest {

    @Autowired StockReservationRepository reservationRepository;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("cutoff 이전에 만료된 RESERVED 만 회수 대상이다 (유예 경계)")
    void onlyExpiredBeforeCutoff() {
        Long overdue = seed(ReservationStatus.RESERVED, LocalDateTime.now().minusMinutes(10));
        Long justExpired = seed(ReservationStatus.RESERVED, LocalDateTime.now().minusMinutes(1));

        // cutoff = now - grace(5분) → 1분 전 만료 건은 아직 Order 취소에게 우선권이 있다.
        List<StockReservation> result =
                reservationRepository.findExpiredReserved(LocalDateTime.now().minusMinutes(5), 100);

        assertThat(orderIds(result)).contains(overdue).doesNotContain(justExpired);
    }

    @Test
    @DisplayName("RESERVED 가 아닌 원장은 만료돼도 회수 대상이 아니다 (판매분·복구분 보호)")
    void nonReservedExcluded() {
        Long confirmed = seed(ReservationStatus.CONFIRMED, LocalDateTime.now().minusMinutes(10));
        Long released = seed(ReservationStatus.RELEASED, LocalDateTime.now().minusMinutes(10));

        List<StockReservation> result =
                reservationRepository.findExpiredReserved(LocalDateTime.now().minusMinutes(5), 100);

        assertThat(orderIds(result)).doesNotContain(confirmed, released);
    }

    @Test
    @DisplayName("lease 미부여(null)는 회수 대상이 아니다 — 회수 근거가 없으므로 안전측")
    void nullExpiryExcluded() {
        Long noLease = seed(ReservationStatus.RESERVED, null);

        List<StockReservation> result =
                reservationRepository.findExpiredReserved(LocalDateTime.now(), 100);

        assertThat(orderIds(result)).doesNotContain(noLease);
    }

    @Test
    @DisplayName("배치 상한을 넘겨 조회하지 않는다 (원장 증가 시 긴 배치 방지, P13)")
    void respectsBatchLimit() {
        for (int i = 0; i < 5; i++) {
            seed(ReservationStatus.RESERVED, LocalDateTime.now().minusMinutes(10 + i));
        }

        List<StockReservation> result =
                reservationRepository.findExpiredReserved(LocalDateTime.now().minusMinutes(5), 3);

        assertThat(result).hasSize(3);
    }

    private List<Long> orderIds(List<StockReservation> reservations) {
        return reservations.stream().map(StockReservation::getOrderId).toList();
    }

    private long nextOrderId = 1000L;

    private Long seed(ReservationStatus status, LocalDateTime expiresAt) {
        Long orderId = nextOrderId++;
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.createNativeQuery("INSERT INTO stock_reservations "
                        + "(order_id, status, items, reserved_at, expires_at, created_at) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6)")
                .setParameter(1, orderId)
                .setParameter(2, status.name())
                .setParameter(3, "[{\"productId\":1,\"quantity\":1}]")
                .setParameter(4, LocalDateTime.now().minusMinutes(40))
                .setParameter(5, expiresAt)
                .setParameter(6, LocalDateTime.now().minusMinutes(40))
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
        return orderId;
    }
}
