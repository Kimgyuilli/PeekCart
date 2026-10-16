package com.peekcart.payment.application;

import com.peekcart.global.outbox.dto.ApprovalRecoverySnapshotPayload;
import com.peekcart.global.outbox.dto.ApprovalRecoveryResultPayload;
import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.toss.TossPaymentClient;
import com.peekcart.payment.infrastructure.toss.TossPaymentSnapshot;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration",
        "app.approval-recovery.approvers=101", "app.approval-recovery.executors=202"})
class ApprovalRecoveryIntegrationTest extends AbstractIntegrationTest {
    @Autowired ApprovalRecoveryService recoveryService;
    @Autowired PaymentApprovalService approvalService;
    @Autowired PaymentApprovalRepository approvals;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean TossPaymentClient pg;

    @BeforeEach
    void seed() {
        cleanDatabase();
        var payment = PaymentFixture.pendingPayment();
        payment.markReadyForPayment(LocalDateTime.now().plusMinutes(10));
        payments.save(payment);
        approvalService.beginApproval(PaymentFixture.DEFAULT_USER_ID, 1L, PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_AMOUNT);
        approvalService.applyStockApprovalResult(1L, true);
        var claimed = approvalService.claimForDispatch(1L).orElseThrow();
        approvalService.finalizeApproval(1L, claimed.getGeneration(), ApprovalOutcome.unresolved("timeout"), 1);
        actor("101", "ADMIN");
        negativePg();
    }
    @AfterEach void clearActor() { SecurityContextHolder.clearContext(); }
    private void actor(String name, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                name, "n/a", List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }
    private void negativePg() {
        String body = "{\"paymentKey\":\"" + PaymentFixture.DEFAULT_PAYMENT_KEY + "\",\"orderId\":\"1\","
                + "\"totalAmount\":" + PaymentFixture.DEFAULT_AMOUNT + ",\"balanceAmount\":0,\"status\":\"ABORTED\"}";
        when(pg.find(PaymentFixture.DEFAULT_PAYMENT_KEY)).thenReturn(Optional.of(new TossPaymentSnapshot("ABORTED", 0, body)));
    }
    private com.peekcart.payment.domain.model.ApprovalRecovery approved() {
        var recovery = recoveryService.request(1L, "PG direct query reconciliation");
        recoveryService.snapshot(new ApprovalRecoverySnapshotPayload(recovery.getId(), 1L, recovery.getGeneration(),
                "APPROVAL_HELD", 4, true, null));
        return recoveryService.approve(recovery.getId(), "Reviewed PG and reservation evidence");
    }

    @Test
    void historicalFailedExecutesOnceAndCompletesOnlyAfterProductAcknowledgement() {
        jdbc.update("UPDATE payment_approvals SET status='FAILED', failure_code='MANUALLY_RESOLVED' WHERE order_id=1");
        var approved = approved();
        actor("202", "ADMIN");
        var executing = recoveryService.execute(approved.getId(), "Execute approved recovery");
        assertThat(approvals.findByOrderId(1L).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.RECOVERY_PENDING);
        assertThat(approvalService.claimForReconcile(1L)).isEmpty();
        assertThat(recoveryService.execute(approved.getId(), "duplicate").getId()).isEqualTo(approved.getId());
        var response = new ApprovalRecoveryResultPayload(executing.getId(), 1L, executing.getGeneration(), true, null);
        recoveryService.result(response);
        recoveryService.result(response);
        assertThat(approvals.findByOrderId(1L).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.approval-recovery.execute'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.failed'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_recovery_audit", Long.class)).isEqualTo(6);
    }

    @Test
    void staleApprovalGenerationAndChangedPgDoNotRelease() {
        var approved = approved();
        actor("202", "ADMIN");
        when(pg.find(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> recoveryService.execute(approved.getId(), "404 cannot prove no charge")).isInstanceOf(RuntimeException.class);
        negativePg();
        jdbc.update("UPDATE payment_approvals SET generation=generation+1 WHERE order_id=1");
        assertThatThrownBy(() -> recoveryService.execute(approved.getId(), "stale approval")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.approval-recovery.execute'", Long.class)).isZero();
    }

    @Test
    void rejectedReservationKeepsProtectedApprovalAndRecordsReview() {
        var approved = approved();
        actor("202", "ADMIN");
        var executing = recoveryService.execute(approved.getId(), "execute");
        recoveryService.result(new ApprovalRecoveryResultPayload(executing.getId(), 1L, executing.getGeneration(), false, "RESERVATION_CHANGED"));
        assertThat(recoveryService.read(executing.getId()).getStatus()).isEqualTo("REVIEW_REQUIRED");
        assertThat(approvals.findByOrderId(1L).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.RECOVERY_PENDING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.failed'", Long.class)).isZero();
    }

    @Test
    void falseResultRequiresFreshSnapshotAndApprovalAndOldGenerationCannotComplete() {
        var first = approved(); actor("202", "ADMIN");
        var executing = recoveryService.execute(first.getId(), "execute first");
        long oldGeneration = executing.getGeneration();
        recoveryService.result(new ApprovalRecoveryResultPayload(first.getId(), 1L, oldGeneration, false, "changed"));
        actor("101", "ADMIN");
        var next = recoveryService.resume(first.getId(), "Fresh PG and reservation reconciliation");
        assertThat(next.getGeneration()).isGreaterThan(oldGeneration);
        assertThat(next.getStatus()).isEqualTo("AWAITING_RESERVATION");
        assertThat(approvalService.claimForReconcile(1L)).isEmpty();
        assertThatThrownBy(() -> recoveryService.result(new ApprovalRecoveryResultPayload(first.getId(), 1L, oldGeneration, true, null)))
                .isInstanceOf(RuntimeException.class);
        recoveryService.snapshot(new ApprovalRecoverySnapshotPayload(next.getId(), 1L, next.getGeneration(), "APPROVAL_HELD", 5, true, null));
        recoveryService.approve(next.getId(), "review new snapshot"); actor("202", "ADMIN");
        var finalExecution = recoveryService.execute(next.getId(), "execute freshly approved snapshot");
        recoveryService.result(new ApprovalRecoveryResultPayload(next.getId(), 1L, finalExecution.getGeneration(), true, null));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.failed'", Long.class)).isEqualTo(1);
    }

    @Test
    void lostReplyRetryKeepsSameFenceAndChangedPgBlocksRedeliveryWithAudit() {
        var first = approved(); actor("202", "ADMIN");
        var executing = recoveryService.execute(first.getId(), "execute");
        var retry = recoveryService.retry(first.getId(), "reply missing");
        assertThat(retry.getGeneration()).isEqualTo(executing.getGeneration());
        assertThat(retry.getReservationVersion()).isEqualTo(executing.getReservationVersion());
        when(pg.find(anyString())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> recoveryService.retry(first.getId(), "PG unknown now")).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_recovery_audit WHERE action='RETRY_REJECTED'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.approval-recovery.execute'", Long.class)).isEqualTo(2);
        assertThat(approvals.findByOrderId(1L).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.RECOVERY_PENDING);
    }

    @Test
    void requestResponseLossReturnsExistingIdAndDoesNotRepublish() {
        var first = recoveryService.request(1L, "request once");
        var second = recoveryService.request(1L, "response lost");
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.approval-recovery.requested'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM approval_recovery_audit WHERE action='REQUEST_DUPLICATE'", Long.class)).isEqualTo(1);
    }

    @Test
    void concurrentExecutionHasOneGenerationOwnerAndOneOutbox() throws Exception {
        var approved = approved();
        var proof = pg.find(PaymentFixture.DEFAULT_PAYMENT_KEY);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        when(pg.find(anyString())).thenAnswer(invocation -> {
            barrier.await(10, java.util.concurrent.TimeUnit.SECONDS);
            return proof;
        });
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            java.util.concurrent.Callable<Boolean> attempt = () -> {
                actor("202", "ADMIN");
                try { recoveryService.execute(approved.getId(), "concurrent execution"); return true; }
                catch (com.peekcart.payment.domain.exception.PaymentException expectedRace) { return false; }
                finally { SecurityContextHolder.clearContext(); }
            };
            var first = pool.submit(attempt);
            var second = pool.submit(attempt);
            assertThat(List.of(first.get(20, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(20, java.util.concurrent.TimeUnit.SECONDS))).containsExactly(true, true);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.approval-recovery.execute'", Long.class)).isEqualTo(1);
            assertThat(approvals.findByOrderId(1L).orElseThrow().getGeneration()).isEqualTo(approved.getGeneration() + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void lateDoneNeverReleasesAndHistoricalFailedUsesOrphanRefundPath() {
        jdbc.update("UPDATE payment_approvals SET status='FAILED', failure_code='MANUALLY_RESOLVED' WHERE order_id=1");
        jdbc.update("UPDATE payments SET status='FAILED' WHERE order_id=1");
        String body = "{\"paymentKey\":\"" + PaymentFixture.DEFAULT_PAYMENT_KEY + "\",\"orderId\":\"1\","
                + "\"totalAmount\":" + PaymentFixture.DEFAULT_AMOUNT + ",\"balanceAmount\":" + PaymentFixture.DEFAULT_AMOUNT + ",\"status\":\"DONE\"}";
        when(pg.find(anyString())).thenReturn(Optional.of(new TossPaymentSnapshot("DONE", 0, body)),
                Optional.of(new TossPaymentSnapshot("ABORTED", 0, "{\"orderId\":\"other\"}")));
        when(pg.parseConfirmed(body)).thenReturn(new com.peekcart.payment.infrastructure.toss.TossConfirmResponse(
                PaymentFixture.DEFAULT_PAYMENT_KEY, "1", "DONE", "CARD", "2026-10-17T00:00:00+09:00"));
        assertThatThrownBy(() -> recoveryService.request(1L, "PG now confirms charge")).isInstanceOf(RuntimeException.class);
        verify(pg, times(1)).find(PaymentFixture.DEFAULT_PAYMENT_KEY);
        assertThat(approvals.findByOrderId(1L).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.SUCCEEDED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment_refunds WHERE order_id=1", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type IN ('payment.failed', 'payment.approval-recovery.execute')", Long.class)).isZero();
    }

    @Test
    void onlyAssignedAdminCanApproveAndExecute() {
        actor("101", "USER");
        assertThatThrownBy(() -> recoveryService.request(1L, "no role")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        actor("303", "ADMIN");
        assertThatThrownBy(() -> recoveryService.request(1L, "not assigned")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        actor("101", "ADMIN");
        var approved = approved();
        assertThatThrownBy(() -> recoveryService.execute(approved.getId(), "same actor")).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
}
