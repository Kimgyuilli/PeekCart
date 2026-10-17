package com.peekcart.payment.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Entity
@Table(name = "approval_recovery_audit")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ApprovalRecoveryAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, updatable = false, length = 36) private String recoveryId;
    @Column(updatable = false) private Long orderId;
    @Column(nullable = false, updatable = false, length = 100) private String actor;
    @Column(nullable = false, updatable = false, length = 40) private String action;
    @Column(nullable = false, updatable = false, length = 500) private String reason;
    @Column(nullable = false, updatable = false, length = 64) private String evidenceDigest;
    @Column(nullable = false, updatable = false, columnDefinition = "LONGTEXT") private String pgEvidence;
    @Column(nullable = false, updatable = false) private long generation;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;

    public static ApprovalRecoveryAudit denied(String recoveryId, Long orderId, String actor, String action, String reason) {
        ApprovalRecoveryAudit audit = new ApprovalRecoveryAudit();
        audit.recoveryId = recoveryId == null ? java.util.UUID.randomUUID().toString() : recoveryId.substring(0, Math.min(recoveryId.length(), 36));
        audit.orderId = orderId; audit.actor = actor; audit.action = action;
        audit.reason = reason.substring(0, Math.min(reason.length(), 500));
        audit.evidenceDigest = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";
        audit.pgEvidence = ""; audit.generation = -1; audit.createdAt = LocalDateTime.now();
        return audit;
    }

    public static ApprovalRecoveryAudit record(ApprovalRecovery recovery, String actor, String action, String reason) {
        if (reason == null || reason.isBlank() || reason.length() > 500) throw new IllegalArgumentException("reason is required (max 500)");
        ApprovalRecoveryAudit audit = new ApprovalRecoveryAudit();
        audit.recoveryId = recovery.getId(); audit.orderId = recovery.getOrderId(); audit.actor = actor; audit.action = action; audit.reason = reason;
        audit.evidenceDigest = recovery.getEvidenceDigest(); audit.pgEvidence = recovery.getPgEvidence();
        audit.generation = recovery.getGeneration(); audit.createdAt = LocalDateTime.now();
        return audit;
    }
}
