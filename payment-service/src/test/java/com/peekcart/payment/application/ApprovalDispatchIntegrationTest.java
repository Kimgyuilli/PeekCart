package com.peekcart.payment.application;

import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import com.peekcart.payment.domain.repository.PaymentRefundRepository;
import com.peekcart.payment.infrastructure.scheduler.ApprovalDispatchScheduler;
import com.peekcart.payment.infrastructure.toss.ApprovalExecutor;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
class ApprovalDispatchIntegrationTest extends AbstractIntegrationTest {
    @Autowired PaymentCommandService commandService;
    @Autowired PaymentApprovalService service;
    @Autowired PaymentRepository payments;
    @Autowired PaymentApprovalRepository approvals;
    @Autowired PaymentRefundRepository refunds;
    @Autowired ApprovalDispatchScheduler dispatcher;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean ApprovalExecutor executor;
    private static final long ORDER = PaymentFixture.DEFAULT_ORDER_ID;

    @BeforeEach
    void setup() {
        cleanDatabase();
        Payment payment = Payment.create(ORDER, PaymentFixture.DEFAULT_USER_ID, PaymentFixture.DEFAULT_AMOUNT);
        payment.markReadyForPayment(LocalDateTime.now().plusMinutes(15));
        payments.save(payment);
    }

    @Test
    @DisplayName("[SAGA-APPROVAL-FENCE-DISPATCH]")
    void acceptsWithoutPgAndDispatchesOnlyAfterProtection() {
        accept();
        assertThat(approvals.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.WAITING_STOCK);
        dispatcher.dispatch();
        verifyNoInteractions(executor);
        assertThat(service.claimForReconcile(ORDER)).isEmpty();
        service.applyStockApprovalResult(ORDER, true);
        when(executor.execute(any())).thenAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(approvals.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.CLAIMED);
            return succeeded();
        });
        dispatcher.dispatch();
        dispatcher.dispatch();
        service.applyStockApprovalResult(ORDER, true);
        verify(executor, times(1)).execute(any());
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void rejectionNeverChargesAndPublishesFailure() {
        accept();
        service.applyStockApprovalResult(ORDER, false);
        service.applyStockApprovalResult(ORDER, true);
        dispatcher.dispatch();
        assertFailedWithoutPg();
    }

    @Test
    void lostReplyExpiresWithoutPgAndLateReplyCannotReopen() {
        accept();
        expireDeadline();
        dispatcher.dispatch();
        service.applyStockApprovalResult(ORDER, true);
        dispatcher.dispatch();
        assertFailedWithoutPg();
    }

    @Test
    void readyButExpiredNeverCharges() {
        accept();
        service.applyStockApprovalResult(ORDER, true);
        expireDeadline();
        dispatcher.dispatch();
        assertFailedWithoutPg();
    }

    @Test
    void cancellationBeforeDispatchSendsFailureEvenWhenPaymentAlreadyCancelled() {
        accept();
        service.applyStockApprovalResult(ORDER, true);
        cancel();
        dispatcher.dispatch();
        assertFailedWithoutPg();
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }

    @Test
    void crashAfterClaimDoesNotCallPgAgain() {
        accept();
        service.applyStockApprovalResult(ORDER, true);
        assertThat(service.claimForDispatch(ORDER)).isPresent();
        jdbc.update("UPDATE payment_approvals SET claimed_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusMinutes(10), ORDER);
        dispatcher.dispatch();
        verifyNoInteractions(executor);
        var recovered = service.claimForReconcile(ORDER).orElseThrow();
        service.finalizeApproval(ORDER, recovered.getGeneration(),
                ApprovalOutcome.succeeded("카드", LocalDateTime.now(), "{}"), 0);
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    void cancellationDuringPgRoutesSuccessToRefund() throws Exception {
        accept();
        service.applyStockApprovalResult(ORDER, true);
        var entered = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        when(executor.execute(any())).thenAnswer(call -> {
            entered.countDown();
            if (!finish.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test PG stalled");
            return succeeded();
        });
        var pool = Executors.newSingleThreadExecutor();
        try {
            var future = pool.submit(dispatcher::dispatch);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            cancel();
            expireDeadline();
            assertThat(service.claimForDispatch(ORDER)).isEmpty();
            finish.countDown();
            future.get(10, TimeUnit.SECONDS);
            assertThat(refunds.findByOrderId(ORDER)).isPresent();
            assertThat(approvals.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.SUCCEEDED);
            verify(executor, times(1)).execute(any());
        } finally {
            finish.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentDispatchClaimsHaveOneOwner() throws Exception {
        accept();
        service.applyStockApprovalResult(ORDER, true);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = pool.submit(() -> { start.await(); return service.claimForDispatch(ORDER).isPresent(); });
            var second = pool.submit(() -> { start.await(); return service.claimForDispatch(ORDER).isPresent(); });
            start.countDown();
            boolean firstWon = first.get(10, TimeUnit.SECONDS);
            boolean secondWon = second.get(10, TimeUnit.SECONDS);
            assertThat(firstWon ^ secondWon).isTrue();
            assertThat(approvals.findByOrderId(ORDER).orElseThrow().getGeneration()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private void accept() {
        assertThat(commandService.confirmPayment(PaymentFixture.DEFAULT_USER_ID,
                PaymentFixture.confirmPaymentCommand()).status()).isEqualTo("PENDING");
    }

    private void expireDeadline() {
        jdbc.update("UPDATE payment_approvals SET dispatch_deadline = ? WHERE order_id = ?",
                LocalDateTime.now().minusSeconds(1), ORDER);
    }

    private void cancel() {
        var payment = payments.findByOrderId(ORDER).orElseThrow();
        payment.cancelBeforePayment();
        payments.save(payment);
    }

    private ApprovalExecutor.CallResult succeeded() {
        return new ApprovalExecutor.CallResult(ApprovalOutcome.succeeded("카드", LocalDateTime.now(), "{}"), 1);
    }

    private void assertFailedWithoutPg() {
        verifyNoInteractions(executor);
        assertThat(approvals.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.failed'", Integer.class))
                .isEqualTo(1);
    }
}
