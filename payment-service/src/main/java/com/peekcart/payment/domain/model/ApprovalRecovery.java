package com.peekcart.payment.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.outbox.dto.ApprovalRecoverySnapshotPayload;
import com.peekcart.payment.domain.exception.PaymentException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "approval_recoveries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalRecovery {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long orderId;
    @Column(nullable = false) private long generation;
    @Column(nullable = false, length = 30) private String status;
    @Column(nullable = false, length = 20) private String pgStatus;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String pgEvidence;
    @Column(nullable = false, length = 64) private String evidenceDigest;
    private Long reservationVersion;
    @Column(length = 20) private String reservationStatus;
    @Column(length = 100) private String approvedBy;
    @Column(length = 100) private String executedBy;
    @Column(nullable = false) private LocalDateTime createdAt;
    private LocalDateTime resolvedAt;

    public static ApprovalRecovery create(PaymentApproval approval, String pgStatus, String evidence, String digest) {
        ApprovalRecovery recovery = new ApprovalRecovery();
        recovery.id = UUID.randomUUID().toString();
        recovery.orderId = approval.getOrderId();
        recovery.generation = approval.getGeneration();
        recovery.status = "AWAITING_RESERVATION";
        recovery.pgStatus = pgStatus;
        recovery.pgEvidence = evidence;
        recovery.evidenceDigest = digest;
        recovery.createdAt = LocalDateTime.now();
        return recovery;
    }

    public void snapshot(ApprovalRecoverySnapshotPayload response) {
        require("AWAITING_RESERVATION");
        if (generation != response.generation() || !orderId.equals(response.orderId())
                || !id.equals(response.recoveryId())) throw new PaymentException(ErrorCode.PAY_004);
        boolean safe = response.approvalFenced() && response.reservationVersion() >= 0
                && ("APPROVAL_HELD".equals(response.status()) && response.refundResult() == null
                || "RELEASED".equals(response.status()));
        if (!safe) { reject(); return; }
        reservationVersion = response.reservationVersion();
        reservationStatus = response.status();
        status = "READY_FOR_APPROVAL";
    }

    public void approve(String actor) {
        require("READY_FOR_APPROVAL");
        approvedBy = actor;
        status = "APPROVED";
    }

    public void execute(String actor, long newGeneration, String evidence, String digest) {
        require("APPROVED");
        if (actor.equals(approvedBy)) throw new PaymentException(ErrorCode.PAY_004);
        executedBy = actor;
        generation = newGeneration;
        pgEvidence = evidence;
        evidenceDigest = digest;
        status = "EXECUTION_REQUESTED";
    }

    public void complete(boolean released) {
        require("EXECUTION_REQUESTED");
        status = released ? "COMPLETED" : "REVIEW_REQUIRED";
        if (released) resolvedAt = LocalDateTime.now();
    }

    public void refreshEvidence(String evidence, String digest) { pgEvidence = evidence; evidenceDigest = digest; }

    public void recordChargedEvidence(String evidence, String digest) {
        pgStatus = "DONE"; pgEvidence = evidence; evidenceDigest = digest;
    }

    public void reject() { status = "REJECTED"; resolvedAt = LocalDateTime.now(); }

    public boolean hasStatus(String expected) { return expected.equals(status); }

    private void require(String expected) {
        if (!expected.equals(status)) throw new PaymentException(ErrorCode.PAY_004);
    }
}
