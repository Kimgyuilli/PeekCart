package com.peekcart.product.application;

import com.peekcart.product.domain.model.StockReservation;
import com.peekcart.product.infrastructure.StockReservationJpaRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
@Import({IntegrationTestConfig.class, SharedContainers.class})
class ReservationVersionIntegrationTest extends AbstractIntegrationTest {
    @Autowired StockReservationJpaRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    private TransactionTemplate tx;

    @BeforeEach
    void seed() {
        cleanDatabase();
        tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> repository.saveAndFlush(
                StockReservation.reserved(1L, "[]", "version-event", Duration.ofMinutes(10))));
    }

    private long version() {
        return repository.findByOrderId(1L).orElseThrow().getVersion();
    }

    @Test
    void nativeAndJpaUpdatesEachAdvanceOnceAndNoOpDoesNotAdvance() {
        assertThat(version()).isZero();
        tx.executeWithoutResult(status -> assertThat(repository.holdForApproval(1L, LocalDateTime.now())).isEqualTo(1));
        assertThat(version()).isEqualTo(1);
        tx.executeWithoutResult(status -> assertThat(repository.holdForApproval(1L, LocalDateTime.now())).isZero());
        assertThat(version()).isEqualTo(1);
        tx.executeWithoutResult(status -> repository.findByOrderId(1L).orElseThrow()
                .recordRefundResult("SUCCEEDED", null, LocalDateTime.now()));
        assertThat(version()).isEqualTo(2);
        tx.executeWithoutResult(status -> assertThat(repository.releaseAfterRefund(1L, LocalDateTime.now())).isEqualTo(1));
        assertThat(version()).isEqualTo(3);
        tx.executeWithoutResult(status -> assertThat(repository.releaseAfterRefund(1L, LocalDateTime.now())).isZero());
        assertThat(version()).isEqualTo(3);
    }

    @Test
    void staleEntityCannotOverwriteNativeCasChange() {
        EntityManager stale = emf.createEntityManager();
        try {
            stale.getTransaction().begin();
            StockReservation snapshot = stale.createQuery("select r from StockReservation r where r.orderId = 1", StockReservation.class)
                    .getSingleResult();
            tx.executeWithoutResult(status -> repository.holdForApproval(1L, LocalDateTime.now()));
            snapshot.recordRefundResult("FAILED", "late", LocalDateTime.now());
            assertThatThrownBy(stale::flush).isInstanceOf(jakarta.persistence.OptimisticLockException.class);
            stale.getTransaction().rollback();
            assertThat(version()).isEqualTo(1);
        } finally {
            if (stale.getTransaction().isActive()) stale.getTransaction().rollback();
            stale.close();
        }
    }
}
