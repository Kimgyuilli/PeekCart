package com.peekcart.product.infrastructure;

import com.peekcart.product.application.InventoryLockFacade;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("Inventory 동시성 테스트")
class InventoryConcurrencyTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired
    InventoryLockFacade inventoryLockFacade;

    private Long productId;
    private Long inventoryId;

    @BeforeEach
    void setUp() {
        cleanDatabase();

        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();

        Category category = Category.create("테스트 카테고리", null);
        em.persist(category);

        Product product = Product.create(category, "테스트 상품", "설명", 10000, null);
        em.persist(product);

        Inventory inventory = Inventory.create(product, 100);
        em.persist(inventory);

        em.getTransaction().commit();
        productId = product.getId();
        inventoryId = inventory.getId();
        em.close();
    }

    @Test
    @DisplayName("동시 재고 차감 시 낙관적 락으로 충돌이 발생하고, 최종 재고가 성공 횟수만큼만 차감된다")
    void concurrentDecrease_optimisticLock_preventsLostUpdate() throws InterruptedException {
        int threadCount = 10;
        int decreasePerThread = 1;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                EntityManager em = emf.createEntityManager();
                try {
                    em.getTransaction().begin();
                    Inventory inv = em.find(Inventory.class, inventoryId);
                    inv.decrease(decreasePerThread);

                    readyLatch.countDown();
                    startLatch.await();

                    em.getTransaction().commit();
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    if (em.getTransaction().isActive()) em.getTransaction().rollback();
                    if (isOptimisticLockException(e)) {
                        conflictCount.incrementAndGet();
                    }
                } finally {
                    em.close();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        // 성공 + 충돌 = 전체 스레드 수
        assertThat(successCount.get() + conflictCount.get()).isEqualTo(threadCount);
        // 충돌이 최소 1건 이상 발생해야 낙관적 락이 작동하는 것
        assertThat(conflictCount.get()).isGreaterThanOrEqualTo(1);

        // 최종 재고 = 초기 재고 - (성공 횟수 × 차감량) — lost update 없음 검증
        EntityManager em = emf.createEntityManager();
        Inventory result = em.find(Inventory.class, inventoryId);
        assertThat(result.getStock()).isEqualTo(100 - (successCount.get() * decreasePerThread));
        em.close();
    }

    @Test
    @DisplayName("50스레드 동시 차감 시 분산 락으로 오버셀링 없이 전부 성공한다")
    void concurrentDecrease_distributedLock_preventsOverselling() throws InterruptedException {
        int threadCount = 50;
        CountDownLatch readyLatch = new CountDownLatch(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    inventoryLockFacade.decreaseStock(productId, 1);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            });
        }

        readyLatch.await();
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        // 50스레드 전부 성공 (분산 락이 순차 실행을 보장)
        assertThat(successCount.get()).isEqualTo(threadCount);
        assertThat(failCount.get()).isZero();

        // 최종 재고 = 초기(100) - 50 = 50 — 오버셀링 0건
        EntityManager em = emf.createEntityManager();
        Inventory result = em.find(Inventory.class, inventoryId);
        assertThat(result.getStock()).isEqualTo(100 - threadCount);
        em.close();
    }

    private boolean isOptimisticLockException(Throwable e) {
        while (e != null) {
            if (e instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                    || e instanceof jakarta.persistence.OptimisticLockException
                    || e instanceof org.hibernate.StaleObjectStateException) {
                return true;
            }
            e = e.getCause();
        }
        return false;
    }
}
