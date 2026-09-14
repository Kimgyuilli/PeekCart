package com.peekcart.payment.infrastructure.toss;

import com.peekcart.payment.application.ApprovalOutcome;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("ApprovalExecutor 단위 테스트 (ADR-0023 D3/D4/D5)")
class ApprovalExecutorTest {

    private static final String DONE_BODY = """
            {"paymentKey":"toss-payment-key-123","status":"DONE","method":"카드",
             "approvedAt":"2026-03-25T14:00:00+09:00"}
            """;

    @Mock TossPaymentClient tossPaymentClient;
    private ApprovalExecutor executor;
    private PaymentApproval approval;

    @BeforeEach
    void setUp() {
        executor = new ApprovalExecutor(tossPaymentClient);
        approval = PaymentFixture.claimedApproval();
    }

    @Test
    @DisplayName("멱등키는 주문 단위로 안정적이다 — 최초 호출과 재실행이 같은 값을 써야 PG 중복 방어가 산다")
    void idempotencyKey_isStablePerOrder() {
        assertThat(executor.idempotencyKey(42L)).isEqualTo("approve-42");
        assertThat(executor.idempotencyKey(42L)).isEqualTo(executor.idempotencyKey(42L));
    }

    @Test
    @DisplayName("execute: 성공 시 method/approvedAt 을 파싱해 SUCCEEDED 로 확정하고 멱등키를 보낸다")
    void execute_success() {
        given(tossPaymentClient.confirm(PaymentFixture.DEFAULT_PAYMENT_KEY, "1",
                PaymentFixture.DEFAULT_AMOUNT, "approve-1"))
                .willReturn(TossOutcome.succeeded(DONE_BODY));
        given(tossPaymentClient.parseConfirmed(DONE_BODY)).willReturn(
                new TossConfirmResponse("toss-payment-key-123", "1", "DONE", "카드", "2026-03-25T14:00:00+09:00"));

        ApprovalExecutor.CallResult result = executor.execute(approval);

        assertThat(result.outcome().kind()).isEqualTo(ApprovalOutcome.Kind.SUCCEEDED);
        assertThat(result.outcome().method()).isEqualTo("카드");
        assertThat(result.outcome().approvedAt()).isNotNull();
    }

    @Test
    @DisplayName("execute: 4xx 영구 실패는 FAILED 로 확정한다")
    void execute_permanentFailure() {
        given(tossPaymentClient.confirm(any(), any(), anyLong(), any()))
                .willReturn(TossOutcome.permanentFailure("INVALID_CARD", "{}"));

        assertThat(executor.execute(approval).outcome().kind()).isEqualTo(ApprovalOutcome.Kind.FAILED);
    }

    @Test
    @DisplayName("execute: 타임아웃/5xx 는 UNRESOLVED — 실패로 닫으면 성립한 과금을 미과금으로 단언하게 된다")
    void execute_transient_isUnresolved() {
        given(tossPaymentClient.confirm(any(), any(), anyLong(), any()))
                .willReturn(TossOutcome.unknown("read timed out"));

        assertThat(executor.execute(approval).outcome().kind()).isEqualTo(ApprovalOutcome.Kind.UNRESOLVED);
    }

    @Test
    @DisplayName("execute: 재시도 루프가 없다 — PG 는 정확히 1회만 호출된다(ADR-0023 D4)")
    void execute_callsPgExactlyOnce() {
        given(tossPaymentClient.confirm(any(), any(), anyLong(), any()))
                .willReturn(TossOutcome.transient_("500", "{}"));

        ApprovalExecutor.CallResult result = executor.execute(approval);

        assertThat(result.attempts()).isEqualTo(1);
        then(tossPaymentClient).should(org.mockito.Mockito.times(1))
                .confirm(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("execute: ALREADY_PROCESSED 는 실패가 아니라 조회로 가른다")
    void execute_alreadyProcessed_verifiesByQuery() {
        given(tossPaymentClient.confirm(any(), any(), anyLong(), any()))
                .willReturn(TossOutcome.alreadyProcessed("{}"));
        given(tossPaymentClient.find(PaymentFixture.DEFAULT_PAYMENT_KEY))
                .willReturn(Optional.of(new TossPaymentSnapshot("DONE", 0L, DONE_BODY)));
        given(tossPaymentClient.parseConfirmed(DONE_BODY)).willReturn(
                new TossConfirmResponse(null, null, "DONE", "카드", "2026-03-25T14:00:00+09:00"));

        assertThat(executor.execute(approval).outcome().kind()).isEqualTo(ApprovalOutcome.Kind.SUCCEEDED);
    }

    @Test
    @DisplayName("verify: 조회만 하고 승인을 재호출하지 않는다 — 재호출은 없던 과금을 만드는 행위다")
    void verify_neverReconfirms() {
        given(tossPaymentClient.find(anyString()))
                .willReturn(Optional.of(new TossPaymentSnapshot("ABORTED", 0L, "{}")));

        ApprovalExecutor.CallResult result = executor.verify(approval);

        assertThat(result.outcome().kind()).isEqualTo(ApprovalOutcome.Kind.FAILED);
        assertThat(result.outcome().code()).isEqualTo("PG_ABORTED");
        then(tossPaymentClient).should(never()).confirm(any(), any(), anyLong(), any());
    }

    @Test
    @DisplayName("verify: PG DONE 이면 과금 성립으로 확정한다")
    void verify_done_isSucceeded() {
        given(tossPaymentClient.find(anyString()))
                .willReturn(Optional.of(new TossPaymentSnapshot("DONE", 0L, DONE_BODY)));
        given(tossPaymentClient.parseConfirmed(DONE_BODY)).willReturn(
                new TossConfirmResponse(null, null, "DONE", "카드", "2026-03-25T14:00:00+09:00"));

        assertThat(executor.verify(approval).outcome().kind()).isEqualTo(ApprovalOutcome.Kind.SUCCEEDED);
    }

    @ParameterizedTest(name = "PG status={0} 이면 닫지 않는다")
    @ValueSource(strings = {"READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT", "SOMETHING_NEW"})
    @DisplayName("verify: 중간/미상 상태는 UNRESOLVED 유지 — 닫으면 뒤늦게 성립한 과금과 영구히 어긋난다")
    void verify_intermediateStates_stayUnresolved(String status) {
        given(tossPaymentClient.find(anyString()))
                .willReturn(Optional.of(new TossPaymentSnapshot(status, 0L, "{}")));

        assertThat(executor.verify(approval).outcome().kind()).isEqualTo(ApprovalOutcome.Kind.UNRESOLVED);
    }

    @Test
    @DisplayName("verify: 조회 자체가 실패하면 UNRESOLVED — 진실이 확정되지 않았다")
    void verify_queryFailure_isUnresolved() {
        given(tossPaymentClient.find(anyString())).willReturn(Optional.empty());

        ApprovalExecutor.CallResult result = executor.verify(approval);

        assertThat(result.outcome().kind()).isEqualTo(ApprovalOutcome.Kind.UNRESOLVED);
        assertThat(result.attempts()).isZero();
    }

    @Test
    @DisplayName("approvedAt 파싱 실패는 성공을 미확정으로 되돌리지 않는다 — 현재 시각으로 대체한다")
    void malformedApprovedAt_stillSucceeds() {
        given(tossPaymentClient.find(anyString()))
                .willReturn(Optional.of(new TossPaymentSnapshot("DONE", 0L, "{}")));
        given(tossPaymentClient.parseConfirmed("{}")).willReturn(
                new TossConfirmResponse(null, null, "DONE", "카드", "not-a-timestamp"));

        ApprovalOutcome outcome = executor.verify(approval).outcome();

        assertThat(outcome.kind()).isEqualTo(ApprovalOutcome.Kind.SUCCEEDED);
        assertThat(outcome.approvedAt()).isNotNull();
    }
}
