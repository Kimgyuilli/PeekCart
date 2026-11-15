package com.peekcart.payment.infrastructure.kafka;

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
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 예약 결과 선도착 marker 의 경합 (D-112).
 *
 * <p>{@code order.created} 와 {@code stock.reservation.result} 는 다른 consumer group 이라 같은 주문을 동시에 처리한다.
 * 한쪽이 handler 를 끝내고 커밋 전에 멈춘 상태에서 다른 쪽을 돌려, 어느 순서에서도 Payment 가 결제 준비 상태가 되고
 * marker 가 남지 않는지 DB 로 확인한다. 각 순서는 한쪽의 잠금 조회를 검증한다(계획서 §3).
 */
@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
@DisplayName("예약 결과 선도착 marker 경합 통합 테스트 (D-112)")
class PaymentReservationMarkerRaceIntegrationTest extends AbstractIntegrationTest {

    private static final long ORDER_ID = 9_112L;
    private static final LocalDateTime EXPIRES_AT = LocalDateTime.of(2030, 1, 1, 12, 0);

    @Autowired PaymentEventConsumer consumer;
    @Autowired TransactionTemplate transactionTemplate;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    @Test
    @DisplayName("생성 쪽이 INSERT 뒤 커밋 전에 멈춰도 예약 결과가 Payment 에 적용된다 — 예약 결과 쪽 잠금 조회 검증")
    void orderCreatedFirst_reservationResultWaitsAndApplies() throws Exception {
        runRace(this::handleOrderCreated, this::handleStockReservationResult);

        assertReadyWithoutMarker();
    }

    @Test
    @DisplayName("예약 결과 쪽이 marker 저장 뒤 커밋 전에 멈춰도 생성 쪽이 marker 를 적용한다 — 생성 쪽 잠금 조회 검증")
    void reservationResultFirst_orderCreatedWaitsAndApplies() throws Exception {
        runRace(this::handleStockReservationResult, this::handleOrderCreated);

        assertReadyWithoutMarker();
    }

    /**
     * {@code first} 를 바깥 트랜잭션 안에서 끝내고 커밋 전에 세운 뒤 {@code second} 를 시작한다.
     * 잠금이 맞다면 {@code second} 는 {@code first} 커밋까지 기다린다. 잠시 뒤 {@code first} 를 풀어 둘 다 끝낸다.
     */
    private void runRace(Runnable first, Runnable second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Void> firstTx = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(s -> {
                first.run();
                firstDone.countDown();
                await(releaseFirst);
            }), pool);
            assertThat(firstDone.await(10, TimeUnit.SECONDS)).as("첫 handler 완료").isTrue();

            CompletableFuture<Void> secondTx = CompletableFuture.runAsync(second, pool);
            // 잠금이 없으면 second 가 이 사이에 끝나 커밋한다. 있으면 first 커밋까지 막혀 있다.
            try {
                secondTx.get(1, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException blocked) {
                // 기대 경로: first 의 잠금에 막혀 있다
            }
            releaseFirst.countDown();
            firstTx.get(20, TimeUnit.SECONDS);
            secondTx.get(20, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            pool.shutdownNow();
        }
    }

    private void handleOrderCreated() {
        consumer.handleOrderCreated("""
                {"eventId":"evt-d112-created","payload":{"orderId":%d,"userId":7,"totalAmount":10000}}
                """.formatted(ORDER_ID));
    }

    private void handleStockReservationResult() {
        consumer.handleStockReservationResult("""
                {"eventId":"evt-d112-reserved","payload":{"orderId":%d,"reserved":true,"reservationExpiresAt":"%s"}}
                """.formatted(ORDER_ID, EXPIRES_AT));
    }

    private void assertReadyWithoutMarker() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, ready_for_payment, reservation_expires_at FROM payments WHERE order_id = ?", ORDER_ID);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("ready_for_payment")).as("결제 준비").isEqualTo(true);
        assertThat(row.get("reservation_expires_at")).isEqualTo(EXPIRES_AT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_reservation_results", Integer.class))
                .as("marker 잔존").isZero();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new IllegalStateException("release 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
