package com.peekcart.payment.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import com.peekcart.payment.infrastructure.toss.ApprovalExecutor;
import com.peekcart.payment.infrastructure.toss.TossPaymentClient;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
class MerchantOrderIdentityIntegrationTest extends AbstractIntegrationTest {
    @Autowired PaymentRepository payments;
    @Autowired PaymentApprovalRepository approvals;
    @Autowired PaymentApprovalService approvalService;
    @Autowired PaymentQueryService query;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;

    @BeforeEach void clean() { cleanDatabase(); }

    private Payment ready(long orderId) {
        Payment payment = Payment.create(orderId, 7L, 10000);
        payment.markReadyForPayment(LocalDateTime.now().plusMinutes(10));
        return payments.save(payment);
    }

    @Test
    void concurrentPrepareReturnsOnePersistedIdentityWithoutApprovalOrOutbox() throws Exception {
        Payment payment = ready(1);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 16; i++) results.add(pool.submit(() -> query.preparePayment(7L, 1L).pgOrderId()));
            for (var result : results) assertThat(result.get()).isEqualTo(payment.getPgOrderId());
        } finally { pool.shutdownNow(); }
        assertThat(payment.getPgOrderId()).matches("pc_[0-9a-f]{32}");
        assertThat(approvals.findByOrderId(1L)).isEmpty();
        assertThat(countOutbox()).isZero();
        assertThat(ready(2).getPgOrderId()).isNotEqualTo(payment.getPgOrderId());
        assertThatThrownBy(() -> jdbc.update("UPDATE payments SET pg_order_id=? WHERE order_id=2", payment.getPgOrderId()))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void prepareRejectsForeignOwnerLegacyAndAlreadyAdmittedWithoutMutatingIdentity() {
        Payment payment = ready(100001);
        assertThatThrownBy(() -> query.preparePayment(8L, 100001L)).isInstanceOf(PaymentException.class);
        approvalService.beginApproval(7L, 100001L, "key", 10000, payment.getPgOrderId());
        assertThatThrownBy(() -> query.preparePayment(7L, 100001L)).isInstanceOf(PaymentException.class);
        ready(100002);
        jdbc.update("UPDATE payments SET pg_order_id=NULL WHERE order_id=100002");
        assertThatThrownBy(() -> query.preparePayment(7L, 100002L))
                .isInstanceOf(PaymentException.class).extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_015);
        assertThat(payments.findByOrderId(100002L).orElseThrow().getPgOrderId()).isNull();
    }

    @Test
    void missingWrongOrForeignIdentityDoesNotChangeKeyLedgerOrOutbox() {
        Payment payment = ready(1);
        String originalKey = payment.getPaymentKey();
        for (String id : new String[]{null, "1", "pc_wrong", ready(2).getPgOrderId()}) {
            assertThatThrownBy(() -> approvalService.beginApproval(7L, 1L, "real-key", 10000, id))
                    .isInstanceOf(PaymentException.class);
        }
        assertThat(payments.findByOrderId(1L).orElseThrow().getPaymentKey()).isEqualTo(originalKey);
        assertThat(approvals.findByOrderId(1L)).isEmpty();
        assertThat(countOutbox()).isZero();
    }

    @Test
    void checkoutConfirmAndRecoveryUseTheSameSnapshotAndDuplicateDoesNotDispatchAgain() {
        ready(1);
        var checkout = query.preparePayment(7L, 1L);
        approvalService.beginApproval(7L, 1L, "real-key", checkout.amount(), checkout.pgOrderId());
        assertThatThrownBy(() -> approvalService.beginApproval(7L, 1L, "other-key", 10000, checkout.pgOrderId()))
                .isInstanceOf(PaymentException.class).extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_013);
        assertThat(approvals.findByOrderId(1L).orElseThrow().getPgOrderId()).isEqualTo(checkout.pgOrderId());
        assertThat(payments.findByOrderId(1L).orElseThrow().getPaymentKey()).isEqualTo("real-key");
        assertThat(countOutbox()).isEqualTo(1);
        approvalService.applyStockApprovalResult(1L, true);
        var claimed = approvalService.claimForDispatch(1L).orElseThrow();
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        String done = "{\"paymentKey\":\"real-key\",\"orderId\":\"" + checkout.pgOrderId()
                + "\",\"totalAmount\":10000,\"status\":\"DONE\",\"method\":\"CARD\"}";
        server.expect(requestTo("http://pg.invalid/payments/confirm"))
                .andExpect(method(HttpMethod.POST)).andExpect(jsonPath("$.orderId").value(checkout.pgOrderId()))
                .andExpect(header("Idempotency-Key", "approve-1"))
                .andRespond(withSuccess(done.replace(checkout.pgOrderId(), "foreign-order"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://pg.invalid/payments/real-key"))
                .andExpect(method(HttpMethod.GET)).andRespond(withSuccess(done, MediaType.APPLICATION_JSON));
        var executor = new ApprovalExecutor(new TossPaymentClient("fixture", "http://pg.invalid", builder, mapper), mapper);
        var initial = executor.execute(claimed);
        assertThat(initial.outcome().kind()).isEqualTo(ApprovalOutcome.Kind.UNRESOLVED);
        approvalService.finalizeApproval(1L, claimed.getGeneration(), initial.outcome(), initial.attempts());
        assertThat(payments.findByOrderId(1L).orElseThrow().getStatus().name()).isEqualTo("PENDING");
        var verified = executor.verify(claimed);
        approvalService.finalizeApproval(1L, claimed.getGeneration(), verified.outcome(), 0);
        assertThat(payments.findByOrderId(1L).orElseThrow().getStatus().name()).isEqualTo("APPROVED");
        server.verify();
    }

    @Test
    void existingNumericCallbacksKeepIdentityButShortUnadmittedLegacyIsRejected() {
        ready(100001);
        ready(1);
        jdbc.update("UPDATE payments SET pg_order_id=NULL");
        var legacy = approvalService.beginApproval(7L, 100001L, "legacy-key", 10000, null);
        assertThat(legacy.effectivePgOrderId()).isEqualTo("100001");
        assertThat(payments.findByOrderId(100001L).orElseThrow().getPgOrderId()).isNull();
        assertThatThrownBy(() -> approvalService.beginApproval(7L, 1L, "short-key", 10000, null))
                .isInstanceOf(PaymentException.class).extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_015);
        assertThat(approvals.findByOrderId(1L)).isEmpty();
    }

    @Test
    void migrationPreservesLegacyValuesAndAddsNullableIdentityWithoutBackfill() throws Exception {
        // Session-local representative pre-V14 tables isolate migration replay from shared application tables.
        try (var connection = jdbc.getDataSource().getConnection()) {
            var local = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            try {
                local.execute("CREATE TEMPORARY TABLE payments (id BIGINT PRIMARY KEY, order_id BIGINT, status VARCHAR(20))");
                local.execute("CREATE TEMPORARY TABLE payment_approvals (id BIGINT PRIMARY KEY, order_id BIGINT, status VARCHAR(20))");
                local.update("INSERT INTO payments VALUES (1,1,'PENDING'),(2,100001,'APPROVED')");
                local.update("INSERT INTO payment_approvals VALUES (1,1,'UNRESOLVED'),(2,100001,'SUCCEEDED')");
                new ResourceDatabasePopulator(new ClassPathResource("db/migration/V14__pg_order_identity.sql"))
                        .execute(local.getDataSource());
                assertThat(local.queryForList("SELECT pg_order_id FROM payments", String.class)).containsOnlyNulls();
                assertThat(local.queryForList("SELECT pg_order_id FROM payment_approvals", String.class)).containsOnlyNulls();
                assertThat(local.queryForList("SELECT order_id FROM payment_approvals ORDER BY id", Long.class)).containsExactly(1L, 100001L);
                assertThat(local.queryForList("SELECT status FROM payments ORDER BY id", String.class)).containsExactly("PENDING", "APPROVED");
            } finally {
                local.execute("DROP TEMPORARY TABLE IF EXISTS payment_approvals");
                local.execute("DROP TEMPORARY TABLE IF EXISTS payments");
            }
        }
    }

    private long countOutbox() { return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class); }
}
