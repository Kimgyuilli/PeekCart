package com.peekcart.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.payment.application.dto.PaymentStatusHint;
import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.toss.ApprovalExecutor;
import com.peekcart.payment.infrastructure.toss.TossPaymentClient;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/** Actual MySQL fence/rollback tests. PG responses below are HTTP fixtures, never real PG evidence. */
@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
class WebhookAdmissionIntegrationTest extends AbstractIntegrationTest {
    private static final long ORDER = 100001L;
    private static final String KEY = "known-payment-key";
    private static final long AMOUNT = 10000L;
    @Autowired WebhookService webhook;
    @Autowired PaymentApprovalService service;
    @Autowired PaymentApprovalRepository approvals;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void cleanup() { cleanDatabase(); }

    @Test void concurrentUntrustedHintsHaveOneDurableAdmission() throws Exception {
        unresolved();
        long outboxBefore = outboxCount();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(8);
        var futures = new ArrayList<Future<?>>();
        try {
            for (int i = 0; i < 8; i++) {
                String status = i % 2 == 0 ? "DONE" : "CANCELED";
                futures.add(pool.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                    webhook.processWebhook(hint(KEY, status));
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        } finally { start.countDown(); pool.shutdownNow(); }
        assertThat(logCount()).isEqualTo(1);
        assertThat(approval().getClaimedAt()).isNull();
        assertThat(approval().getGeneration()).isEqualTo(1);
        assertThat(approval().getStatus()).isEqualTo(ApprovalStatus.UNRESOLVED);
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(outboxCount()).isEqualTo(outboxBefore);

        var claimed = service.claimForReconcile(ORDER).orElseThrow();
        service.finalizeApproval(ORDER, claimed.getGeneration(), ApprovalOutcome.unresolved("still unknown"), 0);
        LocalDateTime secondLease = approval().getClaimedAt();
        webhook.processWebhook(hint(KEY, "ABORTED"));
        assertThat(logCount()).isEqualTo(1);
        assertThat(approval().getClaimedAt()).isEqualTo(secondLease);
    }

    @Test void unknownKeyAndKnownPaymentWithoutApprovalWriteNothing() {
        knownPayment();
        webhook.processWebhook(hint("unknown-key", "DONE"));
        webhook.processWebhook(hint(KEY, "DONE"));
        assertThat(logCount()).isZero();
        assertThat(approvals.findByOrderId(ORDER)).isEmpty();
        assertThat(outboxCount()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = ApprovalStatus.class, names = {"WAITING_STOCK", "READY", "CLAIMED", "SUCCEEDED", "FAILED", "RECOVERY_PENDING"})
    void hintsDoNotTouchOtherApprovalStates(ApprovalStatus status) {
        unresolved();
        jdbc.update("UPDATE payment_approvals SET status=? WHERE order_id=?", status.name(), ORDER);
        LocalDateTime before = approval().getClaimedAt();
        long outboxBefore = outboxCount();
        webhook.processWebhook(hint(KEY, "DONE"));
        assertThat(logCount()).isZero();
        assertThat(approval().getStatus()).isEqualTo(status);
        assertThat(approval().getClaimedAt()).isEqualTo(before);
        assertThat(approval().getGeneration()).isEqualTo(1);
        assertThat(outboxCount()).isEqualTo(outboxBefore);
    }

    @Test void nudgeFailureRollsBackInsertedLogAndAllowsLaterDelivery() {
        unresolved();
        LocalDateTime before = approval().getClaimedAt();
        jdbc.execute("ALTER TABLE payment_approvals ADD CONSTRAINT reject_webhook_nudge CHECK (claimed_at IS NOT NULL)");
        try {
            assertThatThrownBy(() -> webhook.processWebhook(hint(KEY, "DONE"))).isInstanceOf(RuntimeException.class);
        } finally { jdbc.execute("ALTER TABLE payment_approvals DROP CHECK reject_webhook_nudge"); }
        assertThat(logCount()).isZero();
        assertThat(approval().getClaimedAt()).isEqualTo(before);
        webhook.processWebhook(hint(KEY, "DONE"));
        assertThat(logCount()).isEqualTo(1);
        assertThat(approval().getClaimedAt()).isNull();
    }

    @Test void processing409AndForgedDoneRemainUnresolvedUntilServerQuery() {
        PaymentApproval dispatched = dispatched();
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var executor = new ApprovalExecutor(new TossPaymentClient("test-fixture", "http://pg.invalid/v1", builder, new ObjectMapper()), new ObjectMapper());
        server.expect(requestTo("http://pg.invalid/v1/payments/confirm"))
                .andExpect(method(HttpMethod.POST)).andExpect(header("Idempotency-Key", "approve-100001"))
                .andExpect(content().json("{\"paymentKey\":\"" + KEY + "\",\"orderId\":\"" + dispatched.effectivePgOrderId() + "\",\"amount\":10000}"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"IDEMPOTENT_REQUEST_PROCESSING\"}"));
        server.expect(requestTo("http://pg.invalid/v1/payments/" + KEY)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"paymentKey\":\"" + KEY + "\",\"orderId\":\"" + dispatched.effectivePgOrderId()
                        + "\",\"totalAmount\":10000,\"status\":\"IN_PROGRESS\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://pg.invalid/v1/payments/" + KEY)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"paymentKey\":\"" + KEY + "\",\"orderId\":\"" + dispatched.effectivePgOrderId() + "\","
                        + "\"totalAmount\":10000,\"status\":\"DONE\",\"method\":\"카드\","
                        + "\"approvedAt\":\"2026-10-21T10:00:00+09:00\"}", MediaType.APPLICATION_JSON));
        var initial = executor.execute(dispatched);
        assertThat(initial.outcome().kind()).isEqualTo(ApprovalOutcome.Kind.UNRESOLVED);
        service.finalizeApproval(ORDER, dispatched.getGeneration(), initial.outcome(), initial.attempts());
        long before = outboxCount();
        webhook.processWebhook(hint(KEY, "DONE"));
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(approval().getStatus()).isEqualTo(ApprovalStatus.UNRESOLVED);
        assertThat(outboxCount()).isEqualTo(before);
        var claimed = service.claimForReconcile(ORDER).orElseThrow();
        var intermediate = executor.verify(claimed);
        service.finalizeApproval(ORDER, claimed.getGeneration(), intermediate.outcome(), intermediate.attempts());
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.failed'", Long.class)).isZero();
        jdbc.update("UPDATE payment_approvals SET claimed_at=? WHERE order_id=?", LocalDateTime.now().minusHours(1), ORDER);
        var again = service.claimForReconcile(ORDER).orElseThrow();
        var verified = executor.verify(again);
        service.finalizeApproval(ORDER, again.getGeneration(), verified.outcome(), verified.attempts());
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        assertThat(approval().getStatus()).isEqualTo(ApprovalStatus.SUCCEEDED);
        assertThat(logCount()).isEqualTo(1);
        server.verify(); // Exactly one confirm, then queries only; no webhook-triggered PG request.
    }

    private void knownPayment() {
        Payment payment = Payment.create(ORDER, 1L, AMOUNT);
        payment.assignPaymentKey(KEY);
        payment.markReadyForPayment(LocalDateTime.now().plusMinutes(10));
        payments.save(payment);
    }
    private PaymentApproval dispatched() {
        knownPayment();
        service.beginApproval(1L, ORDER, KEY, AMOUNT, payments.findByOrderId(ORDER).orElseThrow().getPgOrderId());
        service.applyStockApprovalResult(ORDER, true);
        return service.claimForDispatch(ORDER).orElseThrow();
    }
    private void unresolved() {
        var dispatched = dispatched();
        service.finalizeApproval(ORDER, dispatched.getGeneration(), ApprovalOutcome.unresolved("timeout"), 1);
    }
    private PaymentApproval approval() { return approvals.findByOrderId(ORDER).orElseThrow(); }
    private long logCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM webhook_logs", Long.class); }
    private long outboxCount() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class); }
    private PaymentStatusHint hint(String key, String status) { return new PaymentStatusHint("PAYMENT_STATUS_CHANGED", key, status); }
}
