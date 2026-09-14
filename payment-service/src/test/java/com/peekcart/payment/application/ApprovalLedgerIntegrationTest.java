package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.payment.domain.model.PaymentRefund;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import com.peekcart.payment.domain.repository.PaymentRefundRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 승인 원장의 fence·claim·nudge 계약 통합 테스트 (계획 P4/P8, ADR-0023).
 *
 * <p><b>단위 mock 으로는 판정할 수 없는 것들만</b> 여기서 본다 — 실제 MySQL 의 유니크 제약,
 * {@code INSERT IGNORE} 의 영향 행 수, 조건부 UPDATE 의 predicate(특히 {@code claimed_at IS NULL}
 * 분기 — 계획 C-9 가 환불 원장에서 발견한 함정이다).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 스케줄러가 테스트 중 원장을 건드리지 않게 한다(경합 판정이 흐려진다).
        "app.refund.dispatch-interval-ms=3600000",
        "app.refund.reconcile-interval-ms=3600000",
        "app.payment.approval.reconcile-interval-ms=3600000"
})
@DisplayName("승인 원장 fence/claim/nudge 통합 테스트")
class ApprovalLedgerIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired PaymentApprovalService approvalService;
    @Autowired PaymentApprovalRepository approvalRepository;
    @Autowired PaymentRefundRepository refundRepository;
    @Autowired PaymentRepository paymentRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("T1 은 실제 paymentKey 를 커밋한다 — 이것이 없으면 사후에 PG 에 물을 수단이 없다")
    void beginApproval_commitsRealPaymentKey() {
        givenReadyPayment();

        PaymentApproval approval = beginApproval();

        assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.CLAIMED);
        assertThat(approval.getGeneration()).isEqualTo(1L);
        assertThat(approval.getPaymentKey()).isEqualTo(PaymentFixture.DEFAULT_PAYMENT_KEY);
        assertThat(approval.getClaimedAt()).isNotNull();
        assertThat(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow().getPaymentKey())
                .isEqualTo(PaymentFixture.DEFAULT_PAYMENT_KEY);
    }

    @Test
    @DisplayName("fence: 같은 주문의 두 번째 승인 시작은 PAY-013 — 중복 과금 창을 로컬에서 닫는다")
    void beginApproval_isFencedPerOrder() {
        givenReadyPayment();
        beginApproval();

        // INSERT IGNORE 가 중복을 0 으로 보고해야만 여기가 던진다. found-rows 시맨틱(중복도 1)이면
        // 두 진입점이 모두 fence 를 잡았다고 믿고 둘 다 PG 로 나간다 — 실제 MySQL 로만 판정된다.
        assertThatThrownBy(this::beginApproval)
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_013);
        assertThat(approvalRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow().getGeneration())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("갓 잡은 claim 은 reconcile 후보가 아니다 — lease 안에 있는 진행 중 호출을 회수하지 않는다")
    void freshClaim_isNotReconcileCandidate() {
        givenReadyPayment();
        beginApproval();

        List<Long> candidates = approvalRepository.findReconcileCandidates(
                LocalDateTime.now().minusMinutes(2), 20);

        assertThat(candidates).isEmpty();
    }

    @Test
    @DisplayName("lease 만료 CLAIMED 는 회수되고 generation 이 오른다 — 옛 owner 의 뒤늦은 확정이 무효가 된다")
    void staleClaim_isReclaimed() {
        givenReadyPayment();
        beginApproval();
        expireLease();

        Optional<PaymentApproval> reclaimed = approvalService.claimForReconcile(PaymentFixture.DEFAULT_ORDER_ID);

        assertThat(reclaimed).isPresent();
        assertThat(reclaimed.get().getGeneration()).isEqualTo(2L);
    }

    @Test
    @DisplayName("웹훅 nudge: UNRESOLVED 의 lease 를 비우면 즉시 후보가 되고 claim 도 잡힌다 (계획 C-9)")
    void webhookNudge_makesUnresolvedImmediatelyClaimable() {
        givenReadyPayment();
        beginApproval();
        approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, 1L,
                ApprovalOutcome.unresolved("timeout"), 1);

        int nudged = approvalService.nudge(PaymentFixture.DEFAULT_PAYMENT_KEY);

        assertThat(nudged).isEqualTo(1);
        // lease 가 아직 만료되지 않은 시점(staleBefore = 지금보다 과거)에도 후보로 잡혀야 한다.
        assertThat(approvalRepository.findReconcileCandidates(LocalDateTime.now().minusMinutes(2), 20))
                .containsExactly(PaymentFixture.DEFAULT_ORDER_ID);
        assertThat(approvalService.claimForReconcile(PaymentFixture.DEFAULT_ORDER_ID)).isPresent();
    }

    @Test
    @DisplayName("웹훅 nudge 는 CLAIMED 를 건드리지 않는다 — 진행 중인 호출과 경쟁시키지 않는다")
    void webhookNudge_ignoresClaimed() {
        givenReadyPayment();
        beginApproval();

        assertThat(approvalService.nudge(PaymentFixture.DEFAULT_PAYMENT_KEY)).isZero();
        assertThat(approvalRepository.findReconcileCandidates(LocalDateTime.now().minusMinutes(2), 20)).isEmpty();
    }

    @Test
    @DisplayName("crash 복구: T1 만 커밋된 건을 조회로 확정하면 payments 가 APPROVED 로 수렴한다 (V-4)")
    void reconcileAfterLostT2_convergesToApproved() {
        givenReadyPayment();
        beginApproval();
        expireLease();

        PaymentApproval reclaimed = approvalService.claimForReconcile(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow();
        approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, reclaimed.getGeneration(),
                ApprovalOutcome.succeeded("카드", LocalDateTime.now(), "{\"status\":\"DONE\"}"), 0);

        assertThat(approvalRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.SUCCEEDED);
        assertThat(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.APPROVED);
    }

    @Test
    @DisplayName("고아 과금: 로컬 CANCELLED + PG 성공이면 환불 원장 REQUESTED 가 생긴다 (V-8)")
    void orphanedCharge_createsRefundRequest() {
        givenReadyPayment();
        beginApproval();

        // PG 호출 중 order.cancelled 도착을 재현한다.
        cancelPayment();
        expireLease();

        PaymentApproval reclaimed = approvalService.claimForReconcile(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow();
        approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, reclaimed.getGeneration(),
                ApprovalOutcome.succeeded("카드", LocalDateTime.now(), "{\"status\":\"DONE\"}"), 0);

        PaymentRefund refund = refundRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow();
        assertThat(refund.getPaymentKey()).isEqualTo(PaymentFixture.DEFAULT_PAYMENT_KEY);
        assertThat(refund.getAmount()).isEqualTo(PaymentFixture.DEFAULT_AMOUNT);
        // 승인은 실제로 성공했다 — 원장은 SUCCEEDED, 되돌리는 일은 환불 원장이 소유한다.
        assertThat(approvalRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow().getStatus())
                .isEqualTo(ApprovalStatus.SUCCEEDED);
        // payments 는 취소된 채로 둔다 — 되돌리는 일은 환불 원장이 소유한다(ADR-0023 D6).
        assertThat(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.CANCELLED);
    }

    // ── helpers ──

    private Payment givenReadyPayment() {
        Payment payment = Payment.create(
                PaymentFixture.DEFAULT_ORDER_ID, PaymentFixture.DEFAULT_USER_ID, PaymentFixture.DEFAULT_AMOUNT);
        payment.markReadyForPayment(null);
        return paymentRepository.save(payment);
    }

    private PaymentApproval beginApproval() {
        return approvalService.beginApproval(PaymentFixture.DEFAULT_USER_ID, PaymentFixture.DEFAULT_ORDER_ID,
                PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_AMOUNT);
    }

    private void cancelPayment() {
        Payment payment = paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID).orElseThrow();
        payment.cancelBeforePayment();
        paymentRepository.save(payment);
    }

    /**
     * lease 만료를 재현한다. {@code claimed_at} 만 과거로 밀고 <b>generation 은 건드리지 않는다</b> —
     * claim 을 한 번 더 통과시키는 방식으로 만들면 fencing token 이 함께 올라가 테스트가 검증하려는
     * generation 증가 자체를 관측할 수 없게 된다.
     */
    private void expireLease() {
        jdbcTemplate.update(
                "UPDATE payment_approvals SET claimed_at = ? WHERE order_id = ?",
                LocalDateTime.now().minusMinutes(10), PaymentFixture.DEFAULT_ORDER_ID);
    }
}
