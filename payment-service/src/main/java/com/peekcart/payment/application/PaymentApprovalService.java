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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 승인 접수/재고 보호 회신/dispatch claim/결과 확정의 트랜잭션 경계 (ADR-0034).
 * PG 호출은 이 클래스 밖에서 수행한다. 실제 키와 원장 claim을 먼저 커밋하므로
 * crash 뒤에는 조회 기반 reconciliation이 진실을 확정할 수 있다 (ADR-0023).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(PaymentApprovalProperties.class)
public class PaymentApprovalService {

    private final PaymentApprovalRepository approvalRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentRefundService refundService;
    private final PaymentOutboxEventPublisher outboxEventPublisher;
    private final PaymentApprovalProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * T1 — 승인 게이트를 검증하고 <b>실제 {@code paymentKey} 와 원장 행을 커밋</b>한다.
     *
     * <p>이 커밋이 D-020 수정의 본체다. 여기가 커밋된 뒤에는 어떤 실패가 나도
     * {@code payment_approvals.payment_key} 로 PG 에 진실을 물을 수 있다.
     *
     * @return Product의 보호 회신을 기다리는 원장 (아직 PG 호출 불가)
     * @throws PaymentException 결제 미존재 {@code PAY-003}, 금액 불일치 {@code PAY-001},
     *                          소유자 불일치 {@code PAY-007}, 예약 미확정 {@code PAY-008},
     *                          결제 불가 상태 {@code PAY-009}, lease 만료 {@code PAY-010},
     *                          진행 중인 승인 존재 {@code PAY-013}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentApproval beginApproval(Long userId, Long orderId, String paymentKey, long amount, String pgOrderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAY_003));

        payment.verifyOwner(userId);
        payment.validateAmount(amount);
        payment.validatePgOrderId(pgOrderId);
        payment.ensureConfirmable(properties.getLeaseApprovalMargin());

        outboxEventPublisher.publishPaymentRequested(payment, userId);
        payment.assignPaymentKey(paymentKey);

        // 주문 단위 중복 접수 fence. 실패하면 새 승인 절차를 시작하지 않는다 —
        // 시작하면 같은 주문에 두 번 과금할 창이 열린다. 멱등키가 PG 측에서 막아 주더라도 그 방어를
        // 로컬 fence 의 대체물로 쓰지 않는다(방어가 한 겹 줄어든다).
        if (approvalRepository.insertWaitingIfAbsent(
                orderId, paymentKey, payment.effectivePgOrderId(), payment.getUserId(), amount, LocalDateTime.now(),
                payment.getReservationExpiresAt() != null ? payment.getReservationExpiresAt()
                        : LocalDateTime.now().plus(properties.getLeaseApprovalMargin())) == 0) {
            log.warn("승인 중복 요청 — 진행 중이거나 종결된 원장이 있다, orderId={}", orderId);
            throw new PaymentException(ErrorCode.PAY_013);
        }
        return approvalRepository.findByOrderId(orderId).orElseThrow();
    }

    /** 재고 보호 회신은 원장만 전이한다. 소비 트랜잭션 안에서 PG를 호출하지 않는다. */
    @Transactional
    public void applyStockApprovalResult(Long orderId, boolean granted) {
        PaymentApproval approval = approvalRepository.findByOrderIdForUpdate(orderId).orElseThrow();
        if (approval.getStatus() != ApprovalStatus.WAITING_STOCK) {
            return;
        }
        if (granted && !approval.dispatchExpired(LocalDateTime.now())) {
            approval.markReady();
        } else {
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
            fail(approval, payment, ApprovalOutcome.failed("STOCK_APPROVAL_REJECTED",
                    "재고 보호 거절 또는 승인 시작 기한 초과"));
        }
    }

    public List<Long> findDispatchCandidates() {
        return approvalRepository.findDispatchCandidates(LocalDateTime.now(), properties.getApproval().getBatchSize());
    }

    /** READY의 최초 실행만 claim한다. 만료 WAITING_STOCK은 PG 없이 실패 회신으로 닫는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentApproval> claimForDispatch(Long orderId) {
        PaymentApproval approval = approvalRepository.findByOrderIdForUpdate(orderId).orElseThrow();
        if (approval.getStatus() != ApprovalStatus.READY && approval.getStatus() != ApprovalStatus.WAITING_STOCK) {
            return Optional.empty();
        }
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        LocalDateTime now = LocalDateTime.now();
        if (payment.getStatus() != PaymentStatus.PENDING || approval.dispatchExpired(now)) {
            fail(approval, payment, ApprovalOutcome.failed("APPROVAL_NOT_DISPATCHABLE",
                    "주문 취소 또는 승인 시작 기한 초과 — PG 호출 없음"));
            return Optional.empty();
        }
        if (approval.getStatus() != ApprovalStatus.READY) {
            return Optional.empty();
        }
        approval.claimForDispatch(now);
        return Optional.of(approval);
    }

    /**
     * T2 — 확정한다. 원장 전이 · {@code payments} 전이 · 회신 Outbox 가 <b>같은 트랜잭션</b>이라
     * 부분 성립이 없다. {@code generation} 이 어긋나면(소유권 상실) 아무것도 하지 않는다.
     *
     * <p>{@code UNRESOLVED} 는 이벤트를 발행하지 않는다 — 결과가 확정되지 않았는데 Order 의 상태를
     * 옮기면 그 상태가 거짓이 된다.
     *
     * @return 반영된 {@code payments} 상태 (호출자가 사용자 응답을 정하는 데 쓴다)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentStatus finalizeApproval(Long orderId, long generation, ApprovalOutcome outcome, int attempts) {
        PaymentApproval approval = approvalRepository.findByOrderIdForUpdate(orderId).orElseThrow();
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();

        if (approval.getStatus() == ApprovalStatus.WAITING_STOCK || approval.getStatus() == ApprovalStatus.READY) {
            throw new IllegalStateException("PG 호출을 claim하지 않은 승인 확정: " + orderId);
        }
        if (approval.isTerminal()) {
            log.debug("이미 종결된 승인 — 확정 no-op, orderId={}", orderId);
            return payment.getStatus();
        }
        if (!approval.ownsGeneration(generation)) {
            // lease 가 만료돼 다른 인스턴스가 소유권을 가져갔다. 늦은 확정이 최신 상태를 덮지 않게 한다.
            log.warn("소유권 상실 — 승인 확정 무시, orderId={}, myGeneration={}, current={}",
                    orderId, generation, approval.getGeneration());
            return payment.getStatus();
        }
        approval.recordAttempts(attempts);

        switch (outcome.kind()) {
            case SUCCEEDED -> succeed(approval, payment, outcome);
            case FAILED -> fail(approval, payment, outcome);
            case UNRESOLVED -> unresolved(approval, outcome);
        }
        return payment.getStatus();
    }

    /** T1(reconciliation) — lease 만료 {@code CLAIMED} · {@code UNRESOLVED} · 웹훅 nudge 건을 claim 한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentApproval> claimForReconcile(Long orderId) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleBefore = now.minus(properties.getApproval().getClaimLease());
        if (approvalRepository.claimForReconcile(orderId, staleBefore, now) == 0) {
            return Optional.empty();
        }
        return approvalRepository.findByOrderId(orderId);
    }

    /**
     * 운영자 수동 처리 (ADR-0035). 보호 건은 감사만 기록하고 조회를 계속한다. <b>{@code UNRESOLVED} 이면서 자동 확정 상한을 넘긴 건만</b>
     * 허용한다 — 진행 중인 {@code CLAIMED} 를 닫으면 성립한 과금을 실패로 확정할 수 있다.
     * 외부 API 로 노출하지 않는다.
     *
     * <p><b>{@code payments} 는 건드리지 않는다.</b> 수동 종결은 "자동으로는 더 못 알아낸다" 는
     * 선언이지 "과금이 없었다" 는 확정이 아니다. 결제를 {@code FAILED} 로 옮기면 사람이 확인하지
     * 않은 추정을 시스템 상태로 굳히게 된다.
     *
     * @throws PaymentException 상태·상한 조건 미충족이면 {@code PAY-004}
     */
    @Transactional
    public void resolveManually(Long orderId, String resolvedBy, String reason) {
        PaymentApproval approval = approvalRepository.findByOrderIdForUpdate(orderId).orElseThrow();
        if (approval.isTerminal()) {
            return;   // 중복 종결 no-op
        }
        if (approval.getStatus() != ApprovalStatus.UNRESOLVED) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        if (LocalDateTime.now().isBefore(
                approval.getRequestedAt().plus(properties.getApproval().getUnresolvedLimit()))) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        approval.resolveManually(resolvedBy, reason);
        log.warn("승인 수동 처리 기록 — orderId={}, by={}, reason={}", orderId, resolvedBy, reason);
    }

    public List<Long> findReconcileCandidates() {
        PaymentApprovalProperties.Approval config = properties.getApproval();
        return approvalRepository.findReconcileCandidates(
                LocalDateTime.now().minus(config.getClaimLease()), config.getBatchSize());
    }

    public Optional<PaymentApproval> find(Long orderId) {
        return approvalRepository.findByOrderId(orderId);
    }

    /** 웹훅 nudge (ADR-0023 D7) — 상태 전이 없이 순회 우선순위만 올린다. */
    @Transactional
    public int nudge(String paymentKey) {
        return approvalRepository.nudgeUnresolvedByPaymentKey(paymentKey);
    }

    /**
     * 승인 성공 확정. 로컬이 이미 {@code PENDING} 이 아니면 <b>고아 과금</b>이므로 환불로 라우팅한다
     * (ADR-0023 D6).
     */
    private void succeed(PaymentApproval approval, Payment payment, ApprovalOutcome outcome) {
        approval.markSucceeded(outcome.detail());

        switch (payment.getStatus()) {
            case PENDING -> {
                payment.approve(outcome.method(), outcome.approvedAt());
                outboxEventPublisher.publishPaymentCompleted(payment, payment.getUserId());
                log.info("승인 성공 확정 — orderId={}", approval.getOrderId());
            }
            // 재확정(중복 도착·reconcile 후행)이다. 이미 정합이라 이벤트를 다시 발행하지 않는다.
            case APPROVED, REFUNDED -> log.debug("승인 재확정 — 로컬 이미 정합, orderId={}, status={}",
                    approval.getOrderId(), payment.getStatus());
            // 과금은 성립했는데 로컬은 취소/실패로 닫혔다 — 되돌릴 방법은 환불뿐이다.
            case CANCELLED, FAILED -> routeOrphanedCharge(approval, payment);
        }
        countResult("succeeded");
    }

    /**
     * 고아 과금을 환불 원장으로 넘긴다 (ADR-0023 D6).
     *
     * <p>승인 원장은 {@code SUCCEEDED} 로 둔다 — 승인은 실제로 성공했고, 그 뒤 환불이 필요하다는
     * 것은 환불 원장이 소유하는 <b>별개의 축</b>이다. 여기를 {@code FAILED} 로 적으면 감사가 거짓이 된다.
     */
    private void routeOrphanedCharge(PaymentApproval approval, Payment payment) {
        log.error("고아 과금 감지 — PG 승인 성립 ↔ 로컬 {} , 환불로 라우팅. orderId={}",
                payment.getStatus(), approval.getOrderId());
        refundService.requestRefundForOrphanedCharge(payment, "PAID_BUT_" + payment.getStatus());
        Counter.builder("payment.approval.orphaned")
                .description("PG 과금은 성립했으나 로컬이 종료된 건 (환불로 라우팅)")
                .tag("local", payment.getStatus().name().toLowerCase())
                .register(meterRegistry)
                .increment();
    }

    private void fail(PaymentApproval approval, Payment payment, ApprovalOutcome outcome) {
        approval.markFailed(outcome.code(), outcome.detail());
        if (payment.getStatus() == PaymentStatus.PENDING) {
            payment.fail();
        }
        // 취소가 먼저 와도 Product의 HELD 해제에는 확정된 실패 회신이 필요하다.
        outboxEventPublisher.publishPaymentFailed(payment, payment.getUserId());
        countResult("failed");
        log.error("승인 실패 확정 — orderId={}, code={}", approval.getOrderId(), outcome.code());
    }

    private void unresolved(PaymentApproval approval, ApprovalOutcome outcome) {
        if (approval.getStatus() == ApprovalStatus.UNRESOLVED) {
            // 재확정 시도가 또 결과 불명 — 상태는 유지하고 사유만 갱신한다.
            approval.recordLastError(outcome.detail());
            return;
        }
        approval.markUnresolved(outcome.detail());
        Counter.builder("payment.approval.unresolved")
                .description("승인 결과 불명 전이 (reconciliation 대상)")
                .register(meterRegistry)
                .increment();
        log.error("승인 결과 불명 — reconciliation 대상, orderId={}", approval.getOrderId());
    }

    private void countResult(String result) {
        Counter.builder("payment.approval.result")
                .description("확정된 승인 결과")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
