package com.peekcart.product.infrastructure;

import com.peekcart.global.lock.DistributedLockManager;
import com.peekcart.product.application.StockReservationService;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ReservationStatus;
import com.peekcart.product.domain.model.StockReservation;
import com.peekcart.product.domain.repository.StockReservationRepository;
import com.peekcart.product.infrastructure.kafka.StockReservationConsumer;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * D-025 — <b>consumer 경계</b>의 재고 동시성 계약 가드 (ADR-0025 D1).
 *
 * <p><b>이 테스트는 두 번 쓰였다.</b> 처음에는 결함의 <b>재현</b>이었다 — 분산 락이 전원 획득되면서도
 * ({@code PRD-004}=0) 커밋은 충돌한다(낙관락 ≥1)는 것을 한 실행 안에 고정했다. 원인은 락이 감싼
 * 구간에 <b>쓰기가 없었다는</b> 것이다: consumer 의 {@code @Transactional} 아래
 * {@code StockReservationService}·{@code InventoryService} 가 REQUIRED 로 참여해 물리 트랜잭션이
 * 하나라, facade 의 {@code finally { unlock }} 이 커밋보다 먼저 돌았다.
 *
 * <p>분산 락을 제거한 지금(ADR-0025 D1) 같은 시나리오가 <b>수정된 계약의 가드</b>가 된다 —
 * 재고 경로는 락을 <b>아예 잡지 않고</b>, 정합성은 {@code @Version} 이 지킨다.
 *
 * <p><b>왜 바깥 트랜잭션이 있는 경로여야 하는가.</b> 삭제된 {@code InventoryLockFacade} 를 직접
 * 호출하던 옛 테스트는 <b>바깥 트랜잭션이 없는</b> 구성이었고, 그 구성에서만 facade javadoc 의 순서가
 * 성립해 50 스레드가 전부 통과했다 — 불변식이 성립하는 <b>유일한 구성만 시험</b>하던 false-green.
 * 그래서 이 테스트는 consumer 빈을 <b>직접</b> 호출한다. Kafka 를 거치지 않지만 트랜잭션 경계는 같다.
 *
 * <p><b>결정적 경합 만들기.</b> 예약 원장 {@code save}(차감 루프 직후, 커밋 전)에 배리어를 걸어
 * 모든 스레드를 "읽기는 끝났고 커밋은 아직" 지점에 모은다. sleep 을 쓰지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(SharedContainers.class)
@DisplayName("D-025 — consumer 경계 재고 동시성 계약 (ADR-0025 D1)")
class StockReservationLockBoundaryIntegrationTest extends AbstractIntegrationTest {

    private static final int INITIAL_STOCK = 100;
    private static final int CONCURRENT_ORDERS = 5;
    private static final int BARRIER_TIMEOUT_SECONDS = 30;

    @Autowired StockReservationConsumer consumer;
    @Autowired ReleaseRunner releaseRunner;

    @MockitoSpyBean DistributedLockManager lockManager;
    @MockitoSpyBean StockReservationRepository reservationRepository;

    private Long productId;
    private Long inventoryId;

    /** "읽기 완료 ~ 커밋 전" 구간에 스레드를 모으는 배리어. null 이면 비활성. */
    private volatile CyclicBarrier preCommitBarrier;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        preCommitBarrier = null;

        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Category category = Category.create("테스트 카테고리", null);
        em.persist(category);
        Product product = Product.create(category, "테스트 상품", "설명", 10_000, null);
        em.persist(product);
        Inventory inventory = Inventory.create(product, INITIAL_STOCK);
        em.persist(inventory);
        em.getTransaction().commit();
        productId = product.getId();
        inventoryId = inventory.getId();
        em.close();

        doAnswer(invocation -> {
            StockReservation saved = invocation.getArgument(0);
            Object result = invocation.callRealMethod();
            CyclicBarrier barrier = preCommitBarrier;
            if (barrier != null && saved.getStatus() == ReservationStatus.RESERVED) {
                barrier.await(BARRIER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }
            return result;
        }).when(reservationRepository).save(any(StockReservation.class));
    }

    /**
     * 동시 예약이 낙관적 락 하나로 수렴한다 — 오버셀링 0, 충돌은 재시도 대상(ADR-0025 D1/D2).
     *
     * <p>충돌이 <b>나는 것</b>이 정상이다. B 안은 충돌을 없애는 설계가 아니라 충돌을 정직하게 드러내고
     * jitter 재시도로 흡수하는 설계다. 여기서 검증하는 것은 충돌 유무가 아니라 <b>충돌이 나도
     * 재고가 깨지지 않는다</b>는 것이다.
     */
    @Test
    @DisplayName("동시 예약은 낙관락으로 수렴한다 — 오버셀링 0, 충돌은 재시도 대상")
    void concurrentReservations_convergeByOptimisticLock() throws Exception {
        preCommitBarrier = new CyclicBarrier(CONCURRENT_ORDERS);

        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger optimisticConflicts = new AtomicInteger();
        AtomicInteger unclassified = new AtomicInteger();

        CountDownLatch done = new CountDownLatch(CONCURRENT_ORDERS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_ORDERS);
        for (int i = 0; i < CONCURRENT_ORDERS; i++) {
            long orderId = 1000L + i;
            executor.submit(() -> {
                try {
                    consumer.handleOrderCreated(orderCreatedMessage("evt-" + orderId, orderId, 1));
                    succeeded.incrementAndGet();
                } catch (Throwable t) {
                    if (isOptimisticLockFailure(t)) {
                        optimisticConflicts.incrementAndGet();
                    } else {
                        unclassified.incrementAndGet();
                    }
                } finally {
                    done.countDown();
                }
            });
        }
        assertThat(done.await(BARRIER_TIMEOUT_SECONDS + 30L, TimeUnit.SECONDS)).isTrue();
        executor.shutdownNow();

        assertThat(optimisticConflicts.get())
                .as("동시 커밋 중 하나만 통과하므로 나머지는 충돌로 떨어진다 — 이것이 설계된 동작이다")
                .isGreaterThanOrEqualTo(1);
        assertThat(unclassified.get()).as("분류되지 않은 예외").isZero();
        assertThat(succeeded.get() + optimisticConflicts.get()).isEqualTo(CONCURRENT_ORDERS);

        EntityManager em = emf.createEntityManager();
        Inventory result = em.find(Inventory.class, inventoryId);
        assertThat(result.getStock())
                .as("성공한 예약 수만큼만 차감 — lost update 없음. 지킨 주체는 @Version 이다")
                .isEqualTo(INITIAL_STOCK - succeeded.get());
        em.close();
    }

    /**
     * 회귀 가드 — 재고를 바꾸는 <b>3경로 전부</b>가 분산 락을 잡지 않는다 (ADR-0025 D1).
     *
     * <p>수정 전에는 차감만 락을 거치고 복구·선검사는 거치지 않는 <b>비대칭</b>이었다. 지금은 셋 다
     * 같은 수단({@code @Version} + 트랜잭션) 아래 있고, 그 대칭이 깨지는 것 —— 즉 누군가 재고 경로에
     * 락을 다시 끼워 넣는 것 —— 을 이 테스트가 막는다.
     */
    @Test
    @DisplayName("회귀 가드 — 차감·선검사·복구 3경로 모두 분산 락을 잡지 않는다")
    void inventoryPathsTakeNoDistributedLock() {
        // 차감 + all-or-nothing 선검사 경로
        consumer.handleOrderCreated(orderCreatedMessage("evt-order-3000", 3000L, 4));
        assertThat(reservationRepository.findByOrderId(3000L)).isPresent()
                .get().extracting(StockReservation::getStatus).isEqualTo(ReservationStatus.RESERVED);

        // 복구 경로
        releaseRunner.release(3000L);

        verify(lockManager, never()).tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class));
        verify(lockManager, never()).unlock(anyString());

        EntityManager em = emf.createEntityManager();
        Inventory result = em.find(Inventory.class, inventoryId);
        assertThat(result.getStock())
                .as("차감 후 복구가 정상 동작 — 락 없이")
                .isEqualTo(INITIAL_STOCK);
        em.close();
    }

    private String orderCreatedMessage(String eventId, long orderId, int quantity) {
        return """
                {"eventId":"%s","payload":{"orderId":%d,"items":[{"productId":%d,"quantity":%d}]}}"""
                .formatted(eventId, orderId, productId, quantity);
    }

    private static boolean isOptimisticLockFailure(Throwable t) {
        for (Throwable e = t; e != null; e = e.getCause()) {
            if (e instanceof org.springframework.orm.ObjectOptimisticLockingFailureException
                    || e instanceof jakarta.persistence.OptimisticLockException
                    || e instanceof org.hibernate.StaleObjectStateException) {
                return true;
            }
        }
        return false;
    }

    /**
     * release 를 {@code order.cancelled} consumer 와 <b>같은 트랜잭션 경계</b>로 실행하는 테스트 전용 빈.
     * 락 관측만이 목적이므로 메시지 파싱·멱등은 태우지 않고 경계만 동일하게 재현한다.
     */
    @TestConfiguration
    static class ReleaseRunnerConfig {
        @Bean
        ReleaseRunner releaseRunner(StockReservationService service) {
            return new ReleaseRunner(service);
        }
    }

    static class ReleaseRunner {
        private final StockReservationService service;

        ReleaseRunner(StockReservationService service) {
            this.service = service;
        }

        @Transactional
        public void release(long orderId) {
            service.release(orderId);
        }
    }
}
