CREATE TABLE approval_recoveries (
    id VARCHAR(36) PRIMARY KEY, order_id BIGINT NOT NULL, generation BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL, pg_status VARCHAR(20) NOT NULL, pg_evidence LONGTEXT NOT NULL,
    evidence_digest VARCHAR(64) NOT NULL, reservation_version BIGINT NULL,
    reservation_status VARCHAR(20) NULL, approved_by VARCHAR(100) NULL, executed_by VARCHAR(100) NULL,
    created_at DATETIME(6) NOT NULL, resolved_at DATETIME(6) NULL,
    INDEX idx_recovery_order_status (order_id, status)
);
CREATE TABLE approval_recovery_audit (
    id BIGINT AUTO_INCREMENT PRIMARY KEY, recovery_id VARCHAR(36) NOT NULL, order_id BIGINT NULL,
    actor VARCHAR(100) NOT NULL, action VARCHAR(40) NOT NULL, reason VARCHAR(500) NOT NULL,
    evidence_digest VARCHAR(64) NOT NULL, pg_evidence LONGTEXT NOT NULL, generation BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    INDEX idx_recovery_audit_id (recovery_id, id)
);
