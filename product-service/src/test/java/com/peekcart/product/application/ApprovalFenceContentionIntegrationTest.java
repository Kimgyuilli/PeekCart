package com.peekcart.product.application;

import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.product.domain.model.*;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.domain.repository.StockReservationRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
class ApprovalFenceContentionIntegrationTest extends AbstractIntegrationTest {
    @Autowired StockReservationService service;
    @Autowired StockReservationRepository reservations;
    @Autowired InventoryRepository inventories;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    private Long productId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        var em = emf.createEntityManager();
        em.getTransaction().begin();
        var category = Category.create("fence", null);
        em.persist(category);
        var product = Product.create(category, "fence", "fence", 1000L, null);
        em.persist(product);
        em.persist(Inventory.create(product, 10));
        em.getTransaction().commit();
        productId = product.getId();
        em.close();
        service.reserve(1L, "reserve", List.of(new ReservedItemPayload(productId, 2)));
    }

    @Test
    @DisplayName("[SAGA-APPROVAL-FENCE-CONTENTION]")
    void actualDatabaseRaceHasOneWinner() throws Exception {
        // Hold the winning row lock until the losing transaction has actually started.
        for (boolean protectionFirst : List.of(true, false)) {
            if (!protectionFirst) {
                cleanDatabase();
                setUp();
            }
            var winnerUpdated = new CountDownLatch(1);
            var loserStarted = new CountDownLatch(1);
            var pool = Executors.newFixedThreadPool(2);
            try {
                var winner = pool.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    if (protectionFirst) service.holdForApproval(1L); else service.release(1L);
                    winnerUpdated.countDown();
                    try { assertThat(loserStarted.await(10, TimeUnit.SECONDS)).isTrue(); }
                    catch (InterruptedException e) { throw new RuntimeException(e); }
                    var rootDataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                            SharedContainers.MYSQL.getJdbcUrl(), "root", SharedContainers.MYSQL.getPassword());
                    var lockInspector = new JdbcTemplate(rootDataSource);
                    org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(10)).untilAsserted(() ->
                            assertThat(lockInspector.queryForObject(
                                    "SELECT COUNT(*) FROM performance_schema.data_lock_waits", Long.class))
                                    .as("loser SQL must actually block on winner's uncommitted row lock")
                                    .isGreaterThan(0));
                    return null;
                }));
                var loser = pool.submit(() -> {
                    assertThat(winnerUpdated.await(10, TimeUnit.SECONDS)).isTrue();
                    loserStarted.countDown();
                    if (protectionFirst) service.release(1L); else service.holdForApproval(1L);
                    return null;
                });
                winner.get(20, TimeUnit.SECONDS);
                loser.get(20, TimeUnit.SECONDS);
                assertThat(reservations.findByOrderId(1L).orElseThrow().getStatus())
                        .isEqualTo(protectionFirst ? ReservationStatus.APPROVAL_HELD : ReservationStatus.RELEASED);
                assertThat(stock()).isEqualTo(protectionFirst ? 8 : 10);
                String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE event_type='stock.approval.result' ORDER BY id DESC LIMIT 1", String.class);
                assertThat(payload).contains("\"granted\":" + protectionFirst);
            } finally { pool.shutdownNow(); }
        }
    }

    @Test
    @DisplayName("[SAGA-APPROVAL-FENCE-TERMINAL-REORDER]")
    void grantedSurvivesCancelExpiryAndRestoresOnceAfterRefund() {
        service.holdForApproval(1L);
        jdbc.update("UPDATE stock_reservations SET expires_at=DATE_SUB(NOW(), INTERVAL 2 DAY) WHERE order_id=1");
        service.release(1L);
        assertThat(service.sweepExpiredLeases()).isZero();
        assertThat(stock()).isEqualTo(8);
        service.confirm(1L);
        service.recordRefundResult(1L, "SUCCEEDED", null, LocalDateTime.now());
        service.recordRefundResult(1L, "SUCCEEDED", null, LocalDateTime.now());
        service.confirm(1L); // completion arrives after refund
        service.releaseAfterPaymentFailed(1L);
        service.holdForApproval(1L);
        assertThat(stock()).isEqualTo(10);
        assertThat(reservations.findByOrderId(1L).orElseThrow().getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void cancellationBeforeReservationCannotBeRevived() {
        service.release(2L);
        service.holdForApproval(2L);
        service.reserve(2L, "late-create", List.of(new ReservedItemPayload(productId, 2)));
        assertThat(reservations.findByOrderId(2L).orElseThrow().getStatus()).isEqualTo(ReservationStatus.CANCEL_REQUESTED);
        assertThat(stock()).isEqualTo(8);
    }

    @Test
    void definitiveFailureReleasesProtectedStockOnce() {
        service.holdForApproval(1L);
        service.releaseAfterPaymentFailed(1L);
        service.releaseAfterPaymentFailed(1L);
        assertThat(stock()).isEqualTo(10);
    }

    private int stock() { return inventories.findByProductId(productId).orElseThrow().getStock(); }
}
