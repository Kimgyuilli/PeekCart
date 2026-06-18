package com.peekcart.product.application;

import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ReservationStatus;
import com.peekcart.product.domain.model.StockReservation;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 재고 예약/복구 Saga 의 서비스 레벨 통합 테스트 (ADR-0012 D3).
 * 실 DB 에서 예약 원장 CAS·UNIQUE 제약·JSON 컬럼·마이그레이션(V5/V6)·재고 차감을 검증한다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
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
    @Autowired com.peekcart.product.infrastructure.kafka.StockConfirmConsumer confirmConsumer;

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
        return outboxEventRepository.findPendingEvents(java.util.List.of("PRODUCT"), 100).stream()
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

    @Test
    @DisplayName("confirm: RESERVED → CONFIRMED 확정 (재고 차감 유지)")
    void confirm_afterReserved_confirms() {
        reservationService.reserve(6L, "evt-6", List.of(new ReservedItemPayload(product1, 10)));

        reservationService.confirm(6L);

        assertThat(reservationRepository.findByOrderId(6L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(stockOf(product1)).isEqualTo(90);  // 확정 후에도 차감 유지
    }

    @Test
    @DisplayName("확정 후 release: CONFIRMED 라 CAS 0건 → 재고 복구 안 함 (판매분 보호)")
    void release_afterConfirm_noRestore() {
        reservationService.reserve(7L, "evt-7", List.of(new ReservedItemPayload(product1, 10)));
        reservationService.confirm(7L);

        reservationService.release(7L);  // 지연/중복 release 도착

        assertThat(stockOf(product1)).isEqualTo(90);  // 복구 안 됨
        assertThat(reservationRepository.findByOrderId(7L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("역순 race: release 가 confirm 보다 먼저(RELEASED) → confirm 은 commit-실패 보상 marker")
    void releaseBeforeConfirm_compensates() {
        reservationService.reserve(8L, "evt-8", List.of(new ReservedItemPayload(product1, 10)));
        reservationService.release(8L);  // confirm 보다 먼저 도착 → RELEASED + 재고 복구
        assertThat(stockOf(product1)).isEqualTo(100);

        reservationService.confirm(8L);  // 결제 완료가 늦게 도착 → 재고 미확정 검출

        StockReservation ledger = reservationRepository.findByOrderId(8L).orElseThrow();
        assertThat(ledger.getStatus()).isEqualTo(ReservationStatus.RELEASED);  // 잘못된 재확정 없음
        assertThat(ledger.getCompensatedAt()).isNotNull();                     // 보상 marker
        assertThat(stockOf(product1)).isEqualTo(100);                          // 재고 변동 없음
    }

    @Test
    @DisplayName("confirm 멱등: 보상 marker 가 이미 있으면 중복 보상하지 않는다")
    void confirm_compensateOnce_idempotent() {
        reservationService.reserve(9L, "evt-9", List.of(new ReservedItemPayload(product1, 10)));
        reservationService.release(9L);
        reservationService.confirm(9L);
        var firstCompensatedAt = reservationRepository.findByOrderId(9L).orElseThrow().getCompensatedAt();

        reservationService.confirm(9L);  // DLQ 재발행 등으로 재실행

        assertThat(reservationRepository.findByOrderId(9L).orElseThrow().getCompensatedAt())
                .isEqualTo(firstCompensatedAt);  // marker 갱신 안 됨
    }

    @Test
    @DisplayName("confirm: 예약 원장 미존재면 transient 로 보고 예외 throw (consumer 재시도)")
    void confirm_noRow_throws() {
        assertThatThrownBy(() -> reservationService.confirm(404L))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("동시성: confirm×2 + release×1 경합 → (CONFIRMED·복구0·보상0) 또는 (RELEASED·복구1·보상1) 한쪽으로만 수렴")
    void confirmReleaseRace_convergesToSingleOutcome() throws InterruptedException {
        reservationService.reserve(10L, "evt-10", List.of(new ReservedItemPayload(product1, 10)));
        assertThat(stockOf(product1)).isEqualTo(90);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        Runnable confirm = () -> { awaitStart(start); reservationService.confirm(10L); };
        Runnable release = () -> { awaitStart(start); reservationService.release(10L); };
        pool.submit(confirm);
        pool.submit(confirm);
        pool.submit(release);
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        StockReservation ledger = reservationRepository.findByOrderId(10L).orElseThrow();
        if (ledger.getStatus() == ReservationStatus.CONFIRMED) {
            assertThat(stockOf(product1)).isEqualTo(90);          // 복구 없음
            assertThat(ledger.getCompensatedAt()).isNull();        // 보상 없음
        } else {
            assertThat(ledger.getStatus()).isEqualTo(ReservationStatus.RELEASED);
            assertThat(stockOf(product1)).isEqualTo(100);          // 1회만 복구 (110 아님)
            assertThat(ledger.getCompensatedAt()).isNotNull();     // 결제완료 검출 보상
        }
    }

    @Test
    @DisplayName("consumer e2e: payment.completed envelope 소비 → 원장 CONFIRMED")
    void confirmConsumer_paymentCompleted_confirmsLedger() {
        reservationService.reserve(11L, "evt-11", List.of(new ReservedItemPayload(product1, 10)));

        confirmConsumer.handlePaymentCompleted(paymentCompletedMessage("pay-evt-11", 11L));

        assertThat(reservationRepository.findByOrderId(11L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    @DisplayName("consumer e2e 멱등: 동일 eventId 중복 소비는 1회만 적용 (processed_events)")
    void confirmConsumer_duplicateEvent_appliedOnce() {
        reservationService.reserve(12L, "evt-12", List.of(new ReservedItemPayload(product1, 10)));
        String msg = paymentCompletedMessage("pay-evt-12", 12L);

        confirmConsumer.handlePaymentCompleted(msg);
        confirmConsumer.handlePaymentCompleted(msg);  // 중복 — processed_events 로 skip

        assertThat(reservationRepository.findByOrderId(12L).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.CONFIRMED);
    }

    private String paymentCompletedMessage(String eventId, long orderId) {
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"payment.completed\","
                + "\"timestamp\":\"2026-01-01T00:00:00\",\"payload\":{\"orderId\":" + orderId + "}}";
    }

    private void awaitStart(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
