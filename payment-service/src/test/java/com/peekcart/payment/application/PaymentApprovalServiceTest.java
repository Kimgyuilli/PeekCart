package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.outbox.PaymentOutboxEventPublisher;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.PaymentFixture;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ServiceTest
@DisplayName("PaymentApprovalService 단위 테스트 — 승인 원장 T1/T2 (ADR-0023)")
class PaymentApprovalServiceTest {

    PaymentApprovalService approvalService;
    @Mock PaymentApprovalRepository approvalRepository;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentRefundService refundService;
    @Mock PaymentOutboxEventPublisher outboxEventPublisher;

    @BeforeEach
    void setUp() {
        PaymentApprovalProperties properties = new PaymentApprovalProperties();
        // 마진 자체의 경계 검증은 PaymentTest 소관이라 여기선 0 으로 둔다.
        properties.setLeaseApprovalMargin(Duration.ZERO);
        properties.getApproval().setClaimLease(Duration.ofMinutes(2));
        properties.getApproval().setUnresolvedLimit(Duration.ofHours(24));
        properties.getApproval().setBatchSize(20);

        approvalService = new PaymentApprovalService(approvalRepository, paymentRepository, refundService,
                outboxEventPublisher, properties, new SimpleMeterRegistry());
    }

    @Nested
    @DisplayName("T1 beginApproval — 게이트 검증과 fence 커밋")
    class BeginApproval {

        @Test
        @DisplayName("성공: 실제 paymentKey 를 심고 payment.requested 를 발행한 뒤 fence 를 잡는다")
        void success() {
            Payment payment = PaymentFixture.readyPaymentWithId();
            given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(payment));
            given(approvalRepository.insertClaimedIfAbsent(anyLong(), any(), anyLong(), anyLong(), any()))
                    .willReturn(1);
            given(approvalRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID))
                    .willReturn(Optional.of(PaymentFixture.claimedApproval()));

            PaymentApproval approval = approvalService.beginApproval(PaymentFixture.DEFAULT_USER_ID,
                    PaymentFixture.DEFAULT_ORDER_ID, PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_AMOUNT);

            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.CLAIMED);
            // D-020 의 본체: PG 호출 전에 실제 키가 로컬에 올라가 있어야 사후 조회가 가능하다.
            assertThat(payment.getPaymentKey()).isEqualTo(PaymentFixture.DEFAULT_PAYMENT_KEY);
            then(outboxEventPublisher).should()
                    .publishPaymentRequested(payment, PaymentFixture.DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("fence 실패(이미 진행 중/종결) 시 PAY-013 — 새 PG 호출을 시작하지 않는다")
        void duplicateFence_throwsPAY013() {
            Payment payment = PaymentFixture.readyPaymentWithId();
            given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(payment));
            given(approvalRepository.insertClaimedIfAbsent(anyLong(), any(), anyLong(), anyLong(), any()))
                    .willReturn(0);

            assertThatThrownBy(() -> approvalService.beginApproval(PaymentFixture.DEFAULT_USER_ID,
                    PaymentFixture.DEFAULT_ORDER_ID, PaymentFixture.DEFAULT_PAYMENT_KEY, PaymentFixture.DEFAULT_AMOUNT))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_013);
        }

        @Test
        @DisplayName("결제 미존재면 PAY-003")
        void notFound_throwsPAY003() {
            given(paymentRepository.findByOrderId(anyLong())).willReturn(Optional.empty());

            assertGateBlocks(ErrorCode.PAY_003, PaymentFixture.DEFAULT_USER_ID, PaymentFixture.DEFAULT_AMOUNT);
        }

        @Test
        @DisplayName("금액 불일치면 PAY-001 — fence 를 잡지 않는다")
        void amountMismatch_throwsPAY001() {
            given(paymentRepository.findByOrderId(anyLong()))
                    .willReturn(Optional.of(PaymentFixture.readyPaymentWithId()));

            assertGateBlocks(ErrorCode.PAY_001, PaymentFixture.DEFAULT_USER_ID, 99_999L);
        }

        @Test
        @DisplayName("본인 결제가 아니면 PAY-007")
        void notOwner_throwsPAY007() {
            given(paymentRepository.findByOrderId(anyLong()))
                    .willReturn(Optional.of(PaymentFixture.readyPaymentWithId()));

            assertGateBlocks(ErrorCode.PAY_007, 2L, PaymentFixture.DEFAULT_AMOUNT);
        }

        @Test
        @DisplayName("예약 미확정이면 PAY-008 — payment.requested 미발행")
        void reservationNotConfirmed_throwsPAY008() {
            given(paymentRepository.findByOrderId(anyLong()))
                    .willReturn(Optional.of(PaymentFixture.pendingPaymentWithId()));

            assertGateBlocks(ErrorCode.PAY_008, PaymentFixture.DEFAULT_USER_ID, PaymentFixture.DEFAULT_AMOUNT);
        }

        @Test
        @DisplayName("주문 취소로 종료된 결제면 PAY-009")
        void cancelled_throwsPAY009() {
            Payment payment = PaymentFixture.readyPaymentWithId();
            payment.cancelBeforePayment();
            given(paymentRepository.findByOrderId(anyLong())).willReturn(Optional.of(payment));

            assertGateBlocks(ErrorCode.PAY_009, PaymentFixture.DEFAULT_USER_ID, PaymentFixture.DEFAULT_AMOUNT);
        }

        /** 게이트에 막히면 fence 도 이벤트도 만들지 않는다 — 승인은 시작조차 되지 않아야 한다. */
        private void assertGateBlocks(ErrorCode expected, Long userId, long amount) {
            assertThatThrownBy(() -> approvalService.beginApproval(userId,
                    PaymentFixture.DEFAULT_ORDER_ID, PaymentFixture.DEFAULT_PAYMENT_KEY, amount))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(expected);
            then(approvalRepository).should(never())
                    .insertClaimedIfAbsent(anyLong(), any(), anyLong(), anyLong(), any());
            then(outboxEventPublisher).should(never()).publishPaymentRequested(any(), any());
        }
    }

    @Nested
    @DisplayName("T2 finalizeApproval — 확정과 고아 라우팅")
    class FinalizeApproval {

        @Test
        @DisplayName("성공: payments APPROVED + payment.completed 발행")
        void succeeded() {
            Payment payment = PaymentFixture.readyPaymentWithId();
            PaymentApproval approval = PaymentFixture.claimedApproval();
            givenLedger(approval, payment);

            PaymentStatus status = approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, 1L,
                    ApprovalOutcome.succeeded("카드", LocalDateTime.now(), "{}"), 1);

            assertThat(status).isEqualTo(PaymentStatus.APPROVED);
            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.SUCCEEDED);
            then(outboxEventPublisher).should().publishPaymentCompleted(payment, PaymentFixture.DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("영구 실패: payments FAILED + payment.failed 발행")
        void failed() {
            Payment payment = PaymentFixture.readyPaymentWithId();
            PaymentApproval approval = PaymentFixture.claimedApproval();
            givenLedger(approval, payment);

            PaymentStatus status = approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, 1L,
                    ApprovalOutcome.failed("INVALID_CARD", "{}"), 1);

            assertThat(status).isEqualTo(PaymentStatus.FAILED);
            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.FAILED);
            then(outboxEventPublisher).should().publishPaymentFailed(payment, PaymentFixture.DEFAULT_USER_ID);
        }

        @Test
        @DisplayName("결과 불명: payments 는 PENDING 유지 · 이벤트 0 — 확정되지 않은 결과로 Order 를 옮기지 않는다")
        void unresolved_publishesNothing() {
            Payment payment = PaymentFixture.readyPaymentWithId();
            PaymentApproval approval = PaymentFixture.claimedApproval();
            givenLedger(approval, payment);

            PaymentStatus status = approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, 1L,
                    ApprovalOutcome.unresolved("timeout"), 1);

            assertThat(status).isEqualTo(PaymentStatus.PENDING);
            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.UNRESOLVED);
            then(outboxEventPublisher).should(never()).publishPaymentCompleted(any(), any());
            then(outboxEventPublisher).should(never()).publishPaymentFailed(any(), any());
        }

        @Test
        @DisplayName("generation 불일치: 소유권을 잃은 확정은 아무것도 바꾸지 않는다")
        void staleGeneration_isNoOp() {
            Payment payment = PaymentFixture.readyPaymentWithId();
            PaymentApproval approval = PaymentFixture.approval(ApprovalStatus.CLAIMED, 5L, LocalDateTime.now());
            givenLedger(approval, payment);

            approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, 1L,
                    ApprovalOutcome.succeeded("카드", LocalDateTime.now(), "{}"), 1);

            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.CLAIMED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
            then(outboxEventPublisher).should(never()).publishPaymentCompleted(any(), any());
        }

        @Test
        @DisplayName("고아 과금(로컬 CANCELLED + PG 성공): 원장은 SUCCEEDED 로 두고 환불로 라우팅한다")
        void orphanedCharge_routesToRefund() {
            Payment payment = PaymentFixture.readyPaymentWithId();
            payment.cancelBeforePayment();   // order.cancelled 가 PG 호출 중 도착
            PaymentApproval approval = PaymentFixture.claimedApproval();
            givenLedger(approval, payment);

            approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, 1L,
                    ApprovalOutcome.succeeded("카드", LocalDateTime.now(), "{}"), 1);

            // 승인은 실제로 성공했다 — 원장을 FAILED 로 적으면 감사가 거짓이 된다(ADR-0023 D6).
            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.SUCCEEDED);
            then(refundService).should().requestRefundForOrphanedCharge(payment, "PAID_BUT_CANCELLED");
            then(outboxEventPublisher).should(never()).publishPaymentCompleted(any(), any());
        }

        @Test
        @DisplayName("재확정(이미 APPROVED): 이벤트를 다시 발행하지 않고 환불로도 보내지 않는다")
        void alreadyApproved_isIdempotent() {
            Payment payment = PaymentFixture.approvedPayment();
            PaymentApproval approval = PaymentFixture.claimedApproval();
            givenLedger(approval, payment);

            approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, 1L,
                    ApprovalOutcome.succeeded("카드", LocalDateTime.now(), "{}"), 1);

            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.SUCCEEDED);
            then(outboxEventPublisher).should(never()).publishPaymentCompleted(any(), any());
            then(refundService).should(never()).requestRefundForOrphanedCharge(any(), any());
        }

        @Test
        @DisplayName("이미 종결된 원장에 도착한 확정은 no-op")
        void terminalLedger_isNoOp() {
            Payment payment = PaymentFixture.readyPaymentWithId();
            PaymentApproval approval = PaymentFixture.approval(ApprovalStatus.SUCCEEDED, 1L, LocalDateTime.now());
            givenLedger(approval, payment);

            approvalService.finalizeApproval(PaymentFixture.DEFAULT_ORDER_ID, 1L,
                    ApprovalOutcome.failed("X", "{}"), 1);

            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.SUCCEEDED);
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        }

        private void givenLedger(PaymentApproval approval, Payment payment) {
            given(approvalRepository.findByOrderIdForUpdate(PaymentFixture.DEFAULT_ORDER_ID))
                    .willReturn(Optional.of(approval));
            given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID))
                    .willReturn(Optional.of(payment));
        }
    }

    @Nested
    @DisplayName("수동 종결 — 상한 전에는 닫지 못한다")
    class ResolveManually {

        @Test
        @DisplayName("UNRESOLVED 가 아니면 PAY-004")
        void notUnresolved_throws() {
            given(approvalRepository.findByOrderIdForUpdate(anyLong()))
                    .willReturn(Optional.of(PaymentFixture.approval(ApprovalStatus.CLAIMED, 1L, LocalDateTime.now())));

            assertThatThrownBy(() -> approvalService.resolveManually(
                    PaymentFixture.DEFAULT_ORDER_ID, "operator", "PG 조회 결과 미과금"))
                    .isInstanceOf(PaymentException.class)
                    .extracting(e -> ((PaymentException) e).getErrorCode())
                    .isEqualTo(ErrorCode.PAY_004);
        }

        @Test
        @DisplayName("상한 초과 + 감사 필드가 있으면 FAILED 로 종결하되 payments 는 건드리지 않는다")
        void overLimit_resolves() {
            PaymentApproval approval = PaymentFixture.approval(
                    ApprovalStatus.UNRESOLVED, 2L, LocalDateTime.now().minusDays(2));
            given(approvalRepository.findByOrderIdForUpdate(anyLong())).willReturn(Optional.of(approval));

            approvalService.resolveManually(PaymentFixture.DEFAULT_ORDER_ID, "operator", "PG 조회 결과 미과금");

            assertThat(approval.getStatus()).isEqualTo(ApprovalStatus.FAILED);
            assertThat(approval.getFailureCode()).isEqualTo("MANUALLY_RESOLVED");
            then(paymentRepository).should(never()).findByOrderId(eq(PaymentFixture.DEFAULT_ORDER_ID));
        }
    }
}
