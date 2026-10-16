package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.outbox.dto.*;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.*;
import com.peekcart.payment.domain.repository.*;
import com.peekcart.payment.infrastructure.outbox.PaymentOutboxEventPublisher;
import com.peekcart.payment.infrastructure.toss.ApprovalRecoveryEvidenceVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
@RequiredArgsConstructor
public class ApprovalRecoveryService {
    private final PaymentApprovalRepository approvals;
    private final ApprovalRecoveryRepository recoveries;
    private final PaymentRefundRepository refunds;
    private final PaymentRepository payments;
    private final ApprovalRecoveryEvidenceVerifier verifier;
    private final ApprovalRecoveryAccess access;
    private final PaymentOutboxEventPublisher publisher;
    private final PlatformTransactionManager transactionManager;
    private final PaymentApprovalService approvalService;

    public ApprovalRecovery read(String id) {
        access.reader();
        return recoveries.find(id).orElseThrow(this::invalid);
    }

    public ApprovalRecovery request(Long orderId, String reason) {
        String actor = access.approver();
        return withAudit(actor, null, orderId, "REQUEST_REJECTED", () -> requestInternal(orderId, reason, actor));
    }

    private ApprovalRecovery requestInternal(Long orderId, String reason, String actor) {
        requireReason(reason);
        var prior = recoveries.latest(orderId);
        if (prior.isPresent() && !prior.get().hasStatus("REJECTED")) {
            return duplicate(prior.get().getId(), actor, "REQUEST_DUPLICATE", reason);
        }
        PaymentApproval snapshot = approvals.findByOrderId(orderId).orElseThrow(this::invalid);
        if (!snapshot.isRecoveryEligible()) throw invalid();
        var evidence = verifier.verify(snapshot).orElseThrow(() -> invalid("PG evidence is absent, inconsistent or not final"));
        if (evidence.charged()) {
            reconcileCharged(snapshot, null, actor, reason, evidence);
            throw invalid();
        }
        return tx().execute(status -> {
            PaymentApproval current = lockedApproval(orderId);
            requireUnchanged(current, snapshot.getGeneration());
            requireRefund(current, evidence);
            if (recoveries.existsActive(orderId)) {
                ApprovalRecovery existing = recoveries.latest(orderId).orElseThrow(this::invalid);
                audit(existing, actor, "REQUEST_DUPLICATE", reason);
                return existing;
            }
            ApprovalRecovery recovery = recoveries.save(ApprovalRecovery.create(current, evidence.status(),
                    evidence.rawResponse(), digest(evidence.rawResponse())));
            audit(recovery, actor, "REQUESTED", reason);
            publisher.publishRecoveryRequested(new ApprovalRecoveryRequestPayload(recovery.getId(), orderId, current.getGeneration()));
            return recovery;
        });
    }

    public ApprovalRecovery approve(String id, String reason) {
        String actor = access.approver();
        return withAudit(actor, id, null, "APPROVE_REJECTED", () -> approveInternal(id, reason, actor));
    }

    private ApprovalRecovery approveInternal(String id, String reason, String actor) {
        requireReason(reason);
        return tx().execute(status -> {
            // Always approval then recovery: all state-changing paths use the same lock order.
            var before = recoveries.find(id).orElseThrow(this::invalid);
            PaymentApproval current = lockedApproval(before.getOrderId());
            ApprovalRecovery recovery = recoveries.findForUpdate(id).orElseThrow(this::invalid);
            requireUnchanged(current, recovery.getGeneration());
            if (recovery.hasStatus("APPROVED")) { audit(recovery, actor, "APPROVE_DUPLICATE", reason); return recovery; }
            recovery.approve(actor); audit(recovery, actor, "APPROVED", reason);
            return recovery;
        });
    }

    public ApprovalRecovery execute(String id, String reason) {
        String actor = access.executor();
        return withAudit(actor, id, null, "EXECUTE_REJECTED", () -> executeInternal(id, reason, actor));
    }

    private ApprovalRecovery executeInternal(String id, String reason, String actor) {
        requireReason(reason);
        ApprovalRecovery snapshot = recoveries.find(id).orElseThrow(this::invalid);
        if (snapshot.hasStatus("EXECUTION_REQUESTED") || snapshot.hasStatus("COMPLETED")) {
            return duplicate(id, actor, "EXECUTE_DUPLICATE", reason);
        }
        if (!snapshot.hasStatus("APPROVED")) throw invalid();
        PaymentApproval approval = approvals.findByOrderId(snapshot.getOrderId()).orElseThrow(this::invalid);
        requireUnchanged(approval, snapshot.getGeneration());
        var evidence = verifier.verify(approval).orElseThrow(() -> invalid("PG evidence is absent, inconsistent or not final"));
        if (evidence.charged()) {
            reconcileCharged(approval, id, actor, reason, evidence);
            return recoveries.find(id).orElseThrow(this::invalid);
        }
        return tx().execute(status -> {
            PaymentApproval current = lockedApproval(approval.getOrderId());
            ApprovalRecovery recovery = recoveries.findForUpdate(id).orElseThrow(this::invalid);
            if (recovery.hasStatus("EXECUTION_REQUESTED") || recovery.hasStatus("COMPLETED")) {
                audit(recovery, actor, "EXECUTE_DUPLICATE", reason); return recovery;
            }
            requireUnchanged(current, recovery.getGeneration());
            if (!recovery.getPgStatus().equals(evidence.status())) throw invalid("PG status changed since approval");
            requireRefund(current, evidence);
            current.beginRecovery(recovery.getGeneration());
            recovery.execute(actor, current.getGeneration(), evidence.rawResponse(), digest(evidence.rawResponse()));
            audit(recovery, actor, "EXECUTION_REQUESTED", reason);
            publisher.publishRecoveryExecute(new ApprovalRecoveryExecutePayload(id, recovery.getOrderId(),
                    current.getGeneration(), recovery.getReservationVersion()));
            return recovery;
        });
    }

    public ApprovalRecovery resume(String id, String reason) {
        String actor = access.approver();
        return withAudit(actor, id, null, "RESUME_REJECTED", () -> {
            requireReason(reason);
            var previous = recoveries.find(id).orElseThrow(this::invalid);
            if (!previous.hasStatus("REVIEW_REQUIRED")) throw invalid("Only an explicit Product rejection may start fresh approval");
            PaymentApproval snapshot = approvals.findByOrderId(previous.getOrderId()).orElseThrow(this::invalid);
            var evidence = verifier.verify(snapshot).orElseThrow(() -> invalid("Fresh PG no-charge evidence required"));
            if (evidence.charged()) throw invalid("PG charge changed; recovery remains held for review");
            return tx().execute(status -> {
                PaymentApproval current = lockedApproval(previous.getOrderId());
                ApprovalRecovery rejected = recoveries.findForUpdate(id).orElseThrow(this::invalid);
                if (!rejected.hasStatus("REVIEW_REQUIRED") || current.getGeneration() != rejected.getGeneration()) throw invalid();
                requireRefund(current, evidence);
                current.restartRecovery(rejected.getGeneration());
                rejected.reject(); audit(rejected, actor, "RESUMED", reason);
                ApprovalRecovery next = recoveries.save(ApprovalRecovery.create(current, evidence.status(), evidence.rawResponse(), digest(evidence.rawResponse())));
                audit(next, actor, "REQUESTED", "Fresh approval after Product rejection: " + id);
                publisher.publishRecoveryRequested(new ApprovalRecoveryRequestPayload(next.getId(), next.getOrderId(), next.getGeneration()));
                return next;
            });
        });
    }

    public ApprovalRecovery retry(String id, String reason) {
        String actor = access.executor();
        return withAudit(actor, id, null, "RETRY_REJECTED", () -> {
            requireReason(reason);
            var previous = recoveries.find(id).orElseThrow(this::invalid);
            if (previous.hasStatus("COMPLETED")) return duplicate(id, actor, "RETRY_DUPLICATE", reason);
            if (!previous.hasStatus("EXECUTION_REQUESTED")) throw invalid("Only an unacknowledged execution can be redelivered");
            PaymentApproval snapshot = approvals.findByOrderId(previous.getOrderId()).orElseThrow(this::invalid);
            var evidence = verifier.verify(snapshot).orElseThrow(() -> invalid("Fresh PG no-charge evidence required"));
            if (evidence.charged() || !previous.getPgStatus().equals(evidence.status())) throw invalid("PG result changed; redelivery forbidden");
            return tx().execute(status -> {
                PaymentApproval current = lockedApproval(previous.getOrderId());
                ApprovalRecovery recovery = recoveries.findForUpdate(id).orElseThrow(this::invalid);
                if (recovery.hasStatus("COMPLETED")) { audit(recovery, actor, "RETRY_DUPLICATE", reason); return recovery; }
                if (!recovery.hasStatus("EXECUTION_REQUESTED") || current.getStatus() != ApprovalStatus.RECOVERY_PENDING
                        || current.getGeneration() != recovery.getGeneration()) throw invalid();
                requireRefund(current, evidence);
                recovery.refreshEvidence(evidence.rawResponse(), digest(evidence.rawResponse()));
                audit(recovery, actor, "REDISPATCHED", reason);
                publisher.publishRecoveryExecute(new ApprovalRecoveryExecutePayload(id, recovery.getOrderId(),
                        recovery.getGeneration(), recovery.getReservationVersion()));
                return recovery;
            });
        });
    }

    @Transactional
    public void snapshot(ApprovalRecoverySnapshotPayload response) {
        PaymentApproval approval = lockedApproval(response.orderId());
        ApprovalRecovery recovery = recoveries.findForUpdate(response.recoveryId()).orElseThrow(this::invalid);
        if (!recovery.hasStatus("AWAITING_RESERVATION")) return;
        if (!approval.isRecoveryEligible() || approval.getGeneration() != response.generation()) {
            recovery.reject(); audit(recovery, "SYSTEM", "STALE_SNAPSHOT", "Approval changed before reservation reply");
            return;
        }
        recovery.snapshot(response); audit(recovery, "SYSTEM", "RESERVATION_SNAPSHOT", "Product reservation reply");
    }

    @Transactional
    public void result(ApprovalRecoveryResultPayload response) {
        PaymentApproval approval = lockedApproval(response.orderId());
        ApprovalRecovery recovery = recoveries.findForUpdate(response.recoveryId()).orElseThrow(this::invalid);
        if (recovery.hasStatus("COMPLETED") || recovery.hasStatus("REVIEW_REQUIRED")) return;
        if (!recovery.getOrderId().equals(response.orderId()) || recovery.getGeneration() != response.generation()
                || approval.getGeneration() != response.generation() || approval.getStatus() != ApprovalStatus.RECOVERY_PENDING) {
            throw invalid();
        }
        recovery.complete(response.released());
        if (response.released()) {
            approval.markFailed("RECOVERY_PROVEN_NO_CHARGE", "Verified PG evidence and Product release acknowledgement");
            Payment payment = payments.findByOrderId(response.orderId()).orElseThrow(this::invalid);
            if (payment.getStatus() == PaymentStatus.PENDING) payment.fail();
            publisher.publishPaymentFailed(payment, approval.getUserId());
        }
        audit(recovery, "SYSTEM", response.released() ? "COMPLETED" : "REVIEW_REQUIRED",
                response.released() ? "Product acknowledged release" : "Product reservation changed; no automatic retry");
    }

    private void reconcileCharged(PaymentApproval snapshot, String recoveryId, String actor, String reason,
                                  ApprovalRecoveryEvidenceVerifier.Evidence evidence) {
        PaymentApproval claimed = tx().execute(status -> {
            PaymentApproval current = lockedApproval(snapshot.getOrderId());
            requireUnchanged(current, snapshot.getGeneration());
            ApprovalRecovery recovery = recoveryId == null
                    ? recoveries.save(ApprovalRecovery.create(current, evidence.status(), evidence.rawResponse(), digest(evidence.rawResponse())))
                    : recoveries.findForUpdate(recoveryId).orElseThrow(this::invalid);
            recovery.recordChargedEvidence(evidence.rawResponse(), digest(evidence.rawResponse()));
            recovery.reject();
            audit(recovery, actor, "PG_CHARGED", reason);
            current.resumeHistoricalForReconcile(snapshot.getGeneration());
            return current;
        });
        approvalService.finalizeApproval(claimed.getOrderId(), claimed.getGeneration(), verifier.succeeded(evidence), 0);
    }

    private ApprovalRecovery duplicate(String id, String actor, String action, String reason) {
        return tx().execute(status -> {
            ApprovalRecovery recovery = recoveries.findForUpdate(id).orElseThrow(this::invalid);
            audit(recovery, actor, action, reason);
            return recovery;
        });
    }

    private <T> T withAudit(String actor, String recoveryId, Long orderId, String action, java.util.function.Supplier<T> operation) {
        try { return operation.get(); }
        catch (RuntimeException rejected) {
            TransactionTemplate auditTransaction = tx();
            auditTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            try {
                auditTransaction.executeWithoutResult(status -> recoveries.append(ApprovalRecoveryAudit.denied(
                        recoveryId, orderId, actor, action, rejected instanceof PaymentException
                                ? rejected.getMessage() : "Operation failed; no recovery completion")));
            } catch (RuntimeException auditFailure) { rejected.addSuppressed(auditFailure); }
            throw rejected;
        }
    }

    private void requireRefund(PaymentApproval approval, ApprovalRecoveryEvidenceVerifier.Evidence evidence) {
        var refund = refunds.findByOrderIdForUpdate(approval.getOrderId());
        if (evidence.refunded()) {
            if (refund.isEmpty() || refund.get().getStatus() != RefundStatus.SUCCEEDED
                    || refund.get().getAmount() != approval.getAmount()) throw invalid();
        } else if (refund.isPresent()) throw invalid();
    }
    private void requireUnchanged(PaymentApproval current, long generation) {
        if (!current.isRecoveryEligible() || current.getGeneration() != generation) throw invalid("Approval state or generation changed");
    }
    private PaymentApproval lockedApproval(Long orderId) { return approvals.findByOrderIdForUpdate(orderId).orElseThrow(this::invalid); }
    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private PaymentException invalid(String reason) { return new PaymentException(ErrorCode.PAY_004, reason); }
    private PaymentException invalid() { return new PaymentException(ErrorCode.PAY_004); }
    private void requireReason(String reason) { if (reason == null || reason.isBlank() || reason.length() > 500) throw invalid(); }
    private void audit(ApprovalRecovery recovery, String actor, String action, String reason) {
        recoveries.append(ApprovalRecoveryAudit.record(recovery, actor, action, reason));
    }
    private String digest(String raw) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
