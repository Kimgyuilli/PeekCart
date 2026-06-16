package com.peekcart.product.application;

import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ReservationStatus;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.domain.repository.StockReservationRepository;
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

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 예약/복구 Saga 의 서비스 레벨 통합 테스트 (ADR-0012 D3).
 * 실 DB 에서 예약 원장 CAS·UNIQUE 제약·JSON 컬럼·마이그레이션(V5/V6)·재고 차감을 검증한다.
 */
@SpringBootTest
@Testcontainers
@Import(IntegrationTestConfig.class)
@DisplayName("재고 예약 Saga 통합 테스트")
class StockReservationSagaIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired StockReservationService reservationService;
    @Autowired InventoryRepository inventoryRepository;
    @Autowired StockReservationRepository reservationRepository;
    @Autowired OutboxEventRepository outboxEventRepository;

    private Long product1;  // 재고 100
    private Long product2;  // 재고 5

    @BeforeEach
    void setUp() {
        cleanDatabase();
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Category category = Category.create("카테고리", null);
        em.persist(category);
        em.flush();

        Product p1 = Product.create(category, "상품1", "설명", 50_000L, null);
        Product p2 = Product.create(category, "상품2", "설명", 30_000L, null);
        em.persist(p1);
        em.persist(p2);
        em.flush();
        product1 = p1.getId();
        product2 = p2.getId();
        em.persist(Inventory.create(p1, 100));
        em.persist(Inventory.create(p2, 5));
        em.getTransaction().commit();
        em.close();
    }

    private int stockOf(Long productId) {
        return inventoryRepository.findByProductId(productId).orElseThrow().getStock();
    }

    private long resultEventCount() {
        return outboxEventRepository.findPendingEvents(100).stream()
                .filter(e -> e.getEventType().equals("stock.reservation.result"))
                .count();
    }

    @Test
    @DisplayName("happy: 전 품목 충분 → 일괄 차감 + 원장 RESERVED + 결과 발행")
    void reserve_happy() {
        reservationService.reserve(1L, "evt-1",
                List.of(new ReservedItemPayload(product1, 10), new ReservedItemPayload(product2, 3)));

        assertThat(stockOf(product1)).isEqualTo(90);
        assertThat(stockOf(product2)).isEqualTo(2);
        assertThat(reservationRepository.findByOrderId(1L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RESERVED);
        assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("all-or-nothing: 2번째 품목 부족 → 차감 0건 + 원장 FAILED + reserved=false")
    void reserve_partialShortage_noDecrement() {
        reservationService.reserve(2L, "evt-2",
                List.of(new ReservedItemPayload(product1, 10), new ReservedItemPayload(product2, 99)));

        assertThat(stockOf(product1)).isEqualTo(100);  // 부분 차감 없음
        assertThat(stockOf(product2)).isEqualTo(5);
        assertThat(reservationRepository.findByOrderId(2L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.FAILED);
        assertThat(resultEventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("예약 후 취소: RESERVED → RELEASED CAS + 재고 복구")
    void release_afterReserved_restores() {
        reservationService.reserve(3L, "evt-3", List.of(new ReservedItemPayload(product1, 10)));
        assertThat(stockOf(product1)).isEqualTo(90);

        reservationService.release(3L);

        assertThat(stockOf(product1)).isEqualTo(100);
        assertThat(reservationRepository.findByOrderId(3L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    @DisplayName("double-release: 두 번째 release 는 복구하지 않는다 (CAS 0)")
    void release_twice_restoresOnce() {
        reservationService.reserve(4L, "evt-4", List.of(new ReservedItemPayload(product1, 10)));
        reservationService.release(4L);
        reservationService.release(4L);

        assertThat(stockOf(product1)).isEqualTo(100);  // 110 아님
    }

    @Test
    @DisplayName("cancel-before-create: 취소 선도착 → tombstone → 이후 예약은 차감 skip")
    void cancelBeforeCreate_tombstone_blocksReserve() {
        reservationService.release(5L);  // 예약 전 취소 도착

        assertThat(reservationRepository.findByOrderId(5L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCEL_REQUESTED);

        reservationService.reserve(5L, "evt-5", List.of(new ReservedItemPayload(product1, 10)));

        assertThat(stockOf(product1)).isEqualTo(100);  // 차감 skip
        assertThat(reservationRepository.findByOrderId(5L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CANCEL_REQUESTED);
    }
}
