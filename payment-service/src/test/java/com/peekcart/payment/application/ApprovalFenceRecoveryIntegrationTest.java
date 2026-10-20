package com.peekcart.payment.application;

import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.scheduler.ApprovalDispatchScheduler;
import com.peekcart.payment.infrastructure.toss.ApprovalExecutor;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
class ApprovalFenceRecoveryIntegrationTest extends AbstractIntegrationTest {
    @Autowired PaymentApprovalService service;
    @Autowired PaymentApprovalRepository approvals;
    @Autowired PaymentRepository payments;
    @Autowired ApprovalDispatchScheduler dispatcher;
    @Autowired JdbcTemplate jdbc;
    @Autowired PaymentQueryService query;
    @MockitoBean ApprovalExecutor executor;
    private static final Long ORDER = PaymentFixture.DEFAULT_ORDER_ID;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        var payment = PaymentFixture.pendingPayment();
        payment.markReadyForPayment(LocalDateTime.now().plusMinutes(10));
        payments.save(payment);
    }

    private com.peekcart.payment.domain.model.PaymentApproval request() {
        return service.beginApproval(PaymentFixture.DEFAULT_USER_ID, ORDER,
                PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_AMOUNT, PaymentFixture.DEFAULT_PG_ORDER_ID);
    }

    @Test
    @DisplayName("[SAGA-APPROVAL-FENCE-MANUAL]")
    void manualHandoffKeepsProtectedUnknownOpenForPgReconciliation() {
        var pending = request();
        service.applyStockApprovalResult(ORDER, true);
        var claimed = service.claimForDispatch(ORDER).orElseThrow();
        service.finalizeApproval(ORDER, claimed.getGeneration(), ApprovalOutcome.unresolved("timeout"), 1);
        jdbc.update("UPDATE payment_approvals SET requested_at=DATE_SUB(NOW(), INTERVAL 2 DAY) WHERE order_id=?", ORDER);
        service.resolveManually(ORDER, "operator", "PG support ticket pending");
        var approval = approvals.findByOrderId(ORDER).orElseThrow();
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.UNRESOLVED);
        assertThat(approval.getResolvedBy()).isEqualTo("operator");
        assertThat(approval.getResolvedAt()).isNull();
        var payment = payments.findByOrderId(ORDER).orElseThrow();
        payment.cancelBeforePayment();
        payments.save(payment);
        var response = query.getPaymentByOrderId(PaymentFixture.DEFAULT_USER_ID, ORDER);
        assertThat(response.status()).isEqualTo("CANCELLED");
        assertThat(response.approvalStatus()).isEqualTo("UNRESOLVED");
        jdbc.update("UPDATE payment_approvals SET claimed_at=DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE order_id=?", ORDER);
        var reclaimed = service.claimForReconcile(ORDER).orElseThrow();
        service.finalizeApproval(ORDER, reclaimed.getGeneration(),
                ApprovalOutcome.succeeded("CARD", LocalDateTime.now(), "{}"), 0);
        assertThat(approvals.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.SUCCEEDED);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.failed'", Long.class)).isZero();
    }

    @Test
    void legacyManualClosureRemainsTerminal() {
        request();
        service.applyStockApprovalResult(ORDER, true);
        var claimed = service.claimForDispatch(ORDER).orElseThrow();
        service.finalizeApproval(ORDER, claimed.getGeneration(), ApprovalOutcome.unresolved("timeout"), 1);
        // Existing pre-V12 rows have NULL deadline; reproduce the migration shape.
        jdbc.update("UPDATE payment_approvals SET dispatch_deadline=NULL, requested_at=DATE_SUB(NOW(), INTERVAL 2 DAY) WHERE order_id=?", ORDER);
        service.resolveManually(ORDER, "operator", "legacy handoff");
        var approval = approvals.findByOrderId(ORDER).orElseThrow();
        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.FAILED);
        assertThat(approval.getFailureCode()).isEqualTo("MANUALLY_RESOLVED");
        assertThat(approval.getResolvedAt()).isNotNull();
        assertThat(service.claimForReconcile(ORDER)).isEmpty();
    }

    @Test
    @DisplayName("[SAGA-APPROVAL-FENCE-PG-SUCCESS-CRASH]")
    void pgSuccessThenCrashBeforeFinalizeRecoversByQueryWithoutSecondApproval() {
        var pending = request();
        service.applyStockApprovalResult(ORDER, true);
        var claimed = service.claimForDispatch(ORDER).orElseThrow();
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        String done = "{\"paymentKey\":\"" + pending.getPaymentKey()
                + "\",\"orderId\":\"" + PaymentFixture.DEFAULT_PG_ORDER_ID + "\",\"status\":\"DONE\",\"method\":\"CARD\","
                + "\"totalAmount\":" + PaymentFixture.DEFAULT_AMOUNT + ",\"approvedAt\":\"2026-10-17T00:00:00+09:00\"}";
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pg/payments/confirm"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.POST))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(done, org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pg/payments/" + pending.getPaymentKey()))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(done, org.springframework.http.MediaType.APPLICATION_JSON));
        var pg = new ApprovalExecutor(new com.peekcart.payment.infrastructure.toss.TossPaymentClient(
                "test", "http://pg", builder, new com.fasterxml.jackson.databind.ObjectMapper()), new com.fasterxml.jackson.databind.ObjectMapper());
        assertThat(pg.execute(claimed).outcome().kind()).isEqualTo(ApprovalOutcome.Kind.SUCCEEDED);
        // Process disappears here: no finalize, DB still CLAIMED, PG has accepted payment.
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);
        jdbc.update("UPDATE payment_approvals SET claimed_at=DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE order_id=?", ORDER);
        var recovered = service.claimForReconcile(ORDER).orElseThrow();
        var result = pg.verify(recovered);
        service.finalizeApproval(ORDER, recovered.getGeneration(), result.outcome(), result.attempts());
        assertThat(approvals.findByOrderId(ORDER).orElseThrow().getDispatchDeadline()).isEqualTo(pending.getDispatchDeadline());
        assertThat(payments.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(PaymentStatus.APPROVED);
        server.verify();
    }

    @Test
    @DisplayName("[SAGA-APPROVAL-FENCE-CRASH]")
    void claimedCrashCannotDispatchAgainAndUnknownRetainsProtectionIdentity() {
        var pending = request();
        service.applyStockApprovalResult(ORDER, true);
        var claimed = service.claimForDispatch(ORDER).orElseThrow();
        dispatcher.dispatch(); // restart after claim, before PG or before recording its success
        verifyNoInteractions(executor);
        jdbc.update("UPDATE payment_approvals SET claimed_at=DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE order_id=?", ORDER);
        var reclaimed = service.claimForReconcile(ORDER).orElseThrow();
        assertThat(reclaimed.getGeneration()).isGreaterThan(claimed.getGeneration());
        assertThat(reclaimed.getDispatchDeadline()).isEqualTo(pending.getDispatchDeadline());
        service.finalizeApproval(ORDER, claimed.getGeneration(), ApprovalOutcome.failed("late", "late"), 1);
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("http://pg/payments/" + pending.getPaymentKey()))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.method(org.springframework.http.HttpMethod.GET))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(org.springframework.http.HttpStatus.NOT_FOUND));
        var pg = new ApprovalExecutor(new com.peekcart.payment.infrastructure.toss.TossPaymentClient(
                "test", "http://pg", builder, new com.fasterxml.jackson.databind.ObjectMapper()), new com.fasterxml.jackson.databind.ObjectMapper());
        var result = pg.verify(reclaimed);
        service.finalizeApproval(ORDER, reclaimed.getGeneration(), result.outcome(), result.attempts());
        server.verify();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='payment.failed'", Long.class)).isZero();
        assertThat(approvals.findByOrderId(ORDER).orElseThrow().getStatus()).isEqualTo(ApprovalStatus.UNRESOLVED);
    }
}
