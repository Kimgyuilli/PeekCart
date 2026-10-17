package com.peekcart.payment.domain.repository;

import com.peekcart.payment.domain.model.ApprovalRecovery;
import com.peekcart.payment.domain.model.ApprovalRecoveryAudit;
import java.util.Optional;

public interface ApprovalRecoveryRepository {
    Optional<ApprovalRecovery> find(String id);
    Optional<ApprovalRecovery> latest(Long orderId);
    Optional<ApprovalRecovery> findForUpdate(String id);
    boolean existsActive(Long orderId);
    ApprovalRecovery save(ApprovalRecovery recovery);
    void append(ApprovalRecoveryAudit audit);
}
