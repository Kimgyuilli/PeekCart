package com.peekcart.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentRefund;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.model.RefundStatus;
import com.peekcart.payment.application.RefundOutcome;

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

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 환불 원장의 fence·claim·확정 계약 통합 테스트 (계획 P14, ADR-0018 D3).
 *
 * <p><b>단위 mock 으로는 판정할 수 없는 것들만</b> 여기서 본다 — 실제 MySQL 의 유니크 제약,
 * 조건부 UPDATE 의 원자성, 서로 다른 트랜잭션의 동시 커밋. ④-b 에서 `@InjectMocks` 테스트가
 * 트랜잭션 경계를 증명하지 못한 전례가 있다.
 */
@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("환불 원장 fence/claim 통합 테스트")
class RefundLedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired PaymentRefundService refundService;
    @Autowired ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("동시 두 진입점이 같은 주문에 요청해도 원장은 1행 — 패자는 예외 없이 no-op")
    void concurrentRequests_singleLedgerRow() throws Exception {
        Payment payment = seedApprovedPayment(1001L);
        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger fenceWinners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);

        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> attemptRequest(payment, barrier, fenceWinners)),
                    pool.submit(() -> attemptRequest(payment, barrier, fenceWinners)));
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);   // 예외 없이 끝나야 한다(패자도 정상 종료)
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(fenceWinners.get()).isEqualTo(1);
        assertThat(countRefunds(payment.getOrderId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("claim CAS: 동시 두 dispatcher 중 정확히 1개만 CLAIMED 를 획득한다")
    void concurrentClaim_onlyOneWins() throws Exception {
        Payment payment = seedApprovedPayment(1002L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");

        CyclicBarrier barrier = new CyclicBarrier(2);
        AtomicInteger claimWinners = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> futures = List.of(
                    pool.submit(() -> attemptClaim(payment.getOrderId(), barrier, claimWinners)),
                    pool.submit(() -> attemptClaim(payment.getOrderId(), barrier, claimWinners)));
            for (Future<?> future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(claimWinners.get()).isEqualTo(1);
        assertThat(currentStatus(payment.getOrderId())).isEqualTo(RefundStatus.CLAIMED);
    }

    @Test
    @DisplayName("성공 확정: 원장 SUCCEEDED · payments REFUNDED · payment.refunded Outbox 가 함께 커밋된다")
    void succeed_commitsLedgerPaymentAndOutboxTogether() throws Exception {
        Payment payment = seedApprovedPayment(1003L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        refundService.claimRequested(payment.getOrderId());

        refundService.finalizeOutcome(payment.getOrderId(), generationOf(payment.getOrderId()), RefundOutcome.succeeded("{\"status\":\"CANCELED\"}"), 1);

        assertThat(currentStatus(payment.getOrderId())).isEqualTo(RefundStatus.SUCCEEDED);
        assertThat(currentPaymentStatus(payment.getOrderId())).isEqualTo(PaymentStatus.REFUNDED);
        List<OutboxEvent> events = findRefundedOutbox(payment.getOrderId());
        assertThat(events).hasSize(1);
        assertThat(objectMapper.readTree(events.get(0).getPayload()).get("payload").get("result").asText())
                .isEqualTo("SUCCEEDED");
    }

    @Test
    @DisplayName("영구 실패 확정: FAILED 회신은 발행하되 payments 는 APPROVED 로 남는다")
    void permanentFailure_publishesFailedAndKeepsApproved() {
        Payment payment = seedApprovedPayment(1004L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        refundService.claimRequested(payment.getOrderId());

        refundService.finalizeOutcome(payment.getOrderId(), generationOf(payment.getOrderId()),
                RefundOutcome.failed("NOT_CANCELABLE_PAYMENT", "{}"), 1);

        assertThat(currentStatus(payment.getOrderId())).isEqualTo(RefundStatus.FAILED);
        // 환불이 안 됐으므로 과금은 살아있다 — payments 를 REFUNDED 로 바꾸면 거짓이 된다.
        assertThat(currentPaymentStatus(payment.getOrderId())).isEqualTo(PaymentStatus.APPROVED);
        assertThat(findRefundedOutbox(payment.getOrderId())).hasSize(1);
    }

    @Test
    @DisplayName("결과 불명: UNRESOLVED 로 남고 회신은 발행하지 않는다 (소비자 원장을 닫지 않는다)")
    void unresolved_doesNotPublish() {
        Payment payment = seedApprovedPayment(1005L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        refundService.claimRequested(payment.getOrderId());

        refundService.finalizeOutcome(payment.getOrderId(), generationOf(payment.getOrderId()), RefundOutcome.unresolved("timeout"), 3);

        assertThat(currentStatus(payment.getOrderId())).isEqualTo(RefundStatus.UNRESOLVED);
        assertThat(findRefundedOutbox(payment.getOrderId())).isEmpty();
    }

    @Test
    @DisplayName("[SAGA-REFUND-CRASH-A] crash (a/b): lease 만료 CLAIMED 는 dispatcher 가 아니라 reconciliation 후보다 (조회 선행 보장)")
    void staleClaim_goesToReconciliationNotDispatcher() {
        Payment payment = seedApprovedPayment(1006L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        refundService.claimRequested(payment.getOrderId());
        assertThat(currentStatus(payment.getOrderId())).isEqualTo(RefundStatus.CLAIMED);

        ageClaim(payment.getOrderId(), LocalDateTime.now().minusHours(1));

        // dispatcher 는 REQUESTED 만 본다 — 여기에 잡히면 조회 없이 재호출하게 된다(ADR-0018 D3 a/b 위반)
        assertThat(refundService.findRequested()).doesNotContain(payment.getOrderId());
        assertThat(refundService.findReconcileCandidates()).contains(payment.getOrderId());
        assertThat(refundService.claimRequested(payment.getOrderId())).isEmpty();
        assertThat(refundService.claimForReconcile(payment.getOrderId())).isPresent();
    }

    @Test
    @DisplayName("fencing: lease 만료로 소유권이 넘어간 뒤 옛 worker 의 확정은 무시된다")
    void staleGeneration_finalizeIsIgnored() {
        Payment payment = seedApprovedPayment(1008L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        long oldGeneration = refundService.claimRequested(payment.getOrderId()).orElseThrow().getGeneration();

        // 다른 인스턴스가 lease 만료 후 소유권을 가져간다
        ageClaim(payment.getOrderId(), LocalDateTime.now().minusHours(1));
        refundService.claimForReconcile(payment.getOrderId());

        // 옛 worker 가 뒤늦게 성공을 확정하려 한다
        refundService.finalizeOutcome(payment.getOrderId(), oldGeneration, RefundOutcome.succeeded("{}"), 1);

        assertThat(currentStatus(payment.getOrderId())).isEqualTo(RefundStatus.CLAIMED);
        assertThat(findRefundedOutbox(payment.getOrderId())).isEmpty();
    }

    @Test
    @DisplayName("userId 계약: 스키마가 NULL 을 거부하고, 서비스도 fence 진입을 차단한다 (이중 방어)")
    void nullUserId_isBlockedBySchemaAndService() {
        Payment payment = seedApprovedPayment(1009L);

        // ① 스키마 계약 — V4 가 payments.user_id 를 NOT NULL 로 고정했다(ADR-0018 D1)
        assertThat(catchThrowable(() -> nullifyUserId(payment.getOrderId()))).isNotNull();

        // ② 서비스 가드 — 스키마가 뚫려도(레거시 DB 등) 환불을 시작하지 않는다.
        //    회신 payload 의 userId 는 Notification 계약상 필수라 null 이면 알림이 유실된다.
        Payment nullUser = mock(Payment.class);
        given(nullUser.getStatus()).willReturn(PaymentStatus.APPROVED);
        given(nullUser.getUserId()).willReturn(null);
        given(nullUser.getOrderId()).willReturn(9999L);

        assertThat(catchThrowable(() -> refundService.requestRefund(nullUser, "PAID_BUT_CANCELLED")))
                .isNotNull();
        assertThat(countRefunds(9999L)).isZero();
    }

    @Test
    @DisplayName("reconciliation claim 은 UNRESOLVED 를 CLAIMED 로 전이한다 (진행 중 수동 종결 차단)")
    void reconcileClaim_movesUnresolvedToClaimed() {
        Payment payment = seedApprovedPayment(1010L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        refundService.claimRequested(payment.getOrderId());
        refundService.finalizeOutcome(payment.getOrderId(), generationOf(payment.getOrderId()),
                RefundOutcome.unresolved("timeout"), 3);
        ageClaim(payment.getOrderId(), LocalDateTime.now().minusHours(1));
        ageRequestedAt(payment.getOrderId(), LocalDateTime.now().minusDays(2));

        refundService.claimForReconcile(payment.getOrderId());

        assertThat(currentStatus(payment.getOrderId())).isEqualTo(RefundStatus.CLAIMED);
        // 조회·재호출이 진행 중인 건은 상한을 넘겼어도 수동 종결 대상이 아니다
        assertThat(catchThrowable(() -> refundService.resolveManually(payment.getOrderId(), "ops", "사유")))
                .isNotNull();
    }

    @Test
    @DisplayName("attempts 는 누적된다 — 조회만 한 확정(0)이 이전 소진 기록을 지우지 않는다")
    void attempts_areAccumulated() {
        Payment payment = seedApprovedPayment(1011L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        refundService.claimRequested(payment.getOrderId());
        refundService.finalizeOutcome(payment.getOrderId(), generationOf(payment.getOrderId()),
                RefundOutcome.unresolved("timeout"), 3);
        assertThat(refundService.find(payment.getOrderId()).orElseThrow().getAttempts()).isEqualTo(3);

        ageClaim(payment.getOrderId(), LocalDateTime.now().minusHours(1));
        refundService.claimForReconcile(payment.getOrderId());
        refundService.finalizeOutcome(payment.getOrderId(), generationOf(payment.getOrderId()),
                RefundOutcome.succeeded("{}"), 0);   // 조회만으로 확정 → 증분 0

        assertThat(refundService.find(payment.getOrderId()).orElseThrow().getAttempts()).isEqualTo(3);
    }

    @Test
    @DisplayName("수동 종결: CLAIMED 는 거부(진행 중일 수 있다), UNRESOLVED + 상한 초과만 허용")
    void manualResolution_onlyUnresolvedOverLimit() {
        Payment payment = seedApprovedPayment(1007L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        refundService.claimRequested(payment.getOrderId());

        // 진행 중(CLAIMED)인 건을 닫으면 실제로 성공한 환불을 실패로 회신할 수 있다
        assertThat(catchThrowable(() -> refundService.resolveManually(payment.getOrderId(), "ops", "사유")))
                .isNotNull();

        refundService.finalizeOutcome(payment.getOrderId(), generationOf(payment.getOrderId()),
                RefundOutcome.unresolved("timeout"), 3);

        // 상한 이내면 아직 자동 확정 기회가 남았다 → 거부
        assertThat(catchThrowable(() -> refundService.resolveManually(payment.getOrderId(), "ops", "사유")))
                .isNotNull();

        ageRequestedAt(payment.getOrderId(), LocalDateTime.now().minusDays(2));

        // actor/사유 누락은 여전히 거부
        assertThat(catchThrowable(() -> refundService.resolveManually(payment.getOrderId(), null, "사유")))
                .isNotNull();

        refundService.resolveManually(payment.getOrderId(), "ops-kim", "PG 콘솔에서 취소 확인 불가");

        PaymentRefund refund = refundService.find(payment.getOrderId()).orElseThrow();
        assertThat(refund.getStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(refund.getResolvedBy()).isEqualTo("ops-kim");
        assertThat(refund.getResolutionReason()).isNotBlank();
    }

    private Throwable catchThrowable(Runnable runnable) {
        try {
            runnable.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    private void attemptRequest(Payment payment, CyclicBarrier barrier, AtomicInteger winners) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            if (refundService.requestRefund(payment, "PAID_BUT_CANCELLED")) {
                winners.incrementAndGet();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void attemptClaim(Long orderId, CyclicBarrier barrier, AtomicInteger winners) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
            if (refundService.claimRequested(orderId).isPresent()) {
                winners.incrementAndGet();
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Payment seedApprovedPayment(Long orderId) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Payment payment = Payment.create(orderId, 42L, 50_000L);
        payment.assignPaymentKey("toss-key-" + orderId);
        payment.approve("카드", LocalDateTime.now());
        em.persist(payment);
        em.getTransaction().commit();
        em.close();
        return payment;
    }

    private long countRefunds(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM payment_refunds WHERE order_id = ?1")
                    .setParameter(1, orderId).getSingleResult()).longValue();
        } finally {
            em.close();
        }
    }

    private RefundStatus currentStatus(Long orderId) {
        return refundService.find(orderId).orElseThrow().getStatus();
    }

    private PaymentStatus currentPaymentStatus(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT p FROM Payment p WHERE p.orderId = :orderId", Payment.class)
                    .setParameter("orderId", orderId)
                    .getSingleResult()
                    .getStatus();
        } finally {
            em.close();
        }
    }

    private List<OutboxEvent> findRefundedOutbox(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                            "SELECT e FROM OutboxEvent e WHERE e.eventType = 'payment.refunded' "
                                    + "AND e.aggregateId = :aggregateId", OutboxEvent.class)
                    .setParameter("aggregateId", orderId.toString())
                    .getResultList();
        } finally {
            em.close();
        }
    }

    private long generationOf(Long orderId) {
        return refundService.find(orderId).orElseThrow().getGeneration();
    }

    private void nullifyUserId(Long orderId) {
        executeNative("UPDATE payments SET user_id = NULL WHERE order_id = ?2", null, orderId);
    }

    private void ageRequestedAt(Long orderId, LocalDateTime requestedAt) {
        executeNative("UPDATE payment_refunds SET requested_at = ?1 WHERE order_id = ?2", requestedAt, orderId);
    }

    private void executeNative(String sql, Object param1, Long orderId) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        var query = em.createNativeQuery(sql);
        if (sql.contains("?1")) {
            query.setParameter(1, param1);
        }
        query.setParameter(2, orderId);
        query.executeUpdate();
        em.getTransaction().commit();
        em.close();
    }

    private void ageClaim(Long orderId, LocalDateTime claimedAt) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.createNativeQuery("UPDATE payment_refunds SET claimed_at = ?1 WHERE order_id = ?2")
                .setParameter(1, claimedAt)
                .setParameter(2, orderId)
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
    }
}
