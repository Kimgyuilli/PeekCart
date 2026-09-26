package com.peekcart.product.infrastructure;

import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(SharedContainers.class)
@DisplayName("Inventory 동시성 테스트 — 수단은 @Version 하나 (ADR-0025 D1)")
class InventoryConcurrencyTest extends AbstractIntegrationTest {

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
