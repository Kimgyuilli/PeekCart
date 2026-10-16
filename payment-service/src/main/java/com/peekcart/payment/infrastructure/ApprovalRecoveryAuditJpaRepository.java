package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.ApprovalRecoveryAudit;
import org.springframework.data.repository.Repository;

public interface ApprovalRecoveryAuditJpaRepository extends Repository<ApprovalRecoveryAudit, Long> {
    ApprovalRecoveryAudit save(ApprovalRecoveryAudit audit);
}
