package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.ApprovalRecovery;
import com.peekcart.payment.domain.model.ApprovalRecoveryAudit;
import com.peekcart.payment.domain.repository.ApprovalRecoveryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class ApprovalRecoveryRepositoryImpl implements ApprovalRecoveryRepository {
    private final ApprovalRecoveryJpaRepository repository;
    private final ApprovalRecoveryAuditJpaRepository audit;
    public Optional<ApprovalRecovery> latest(Long orderId) { return repository.findFirstByOrderIdOrderByCreatedAtDesc(orderId); }
    public Optional<ApprovalRecovery> find(String id) { return repository.findById(id); }
    public Optional<ApprovalRecovery> findForUpdate(String id) { return repository.findForUpdate(id); }
    public boolean existsActive(Long orderId) { return repository.existsActive(orderId); }
    public ApprovalRecovery save(ApprovalRecovery recovery) { return repository.save(recovery); }
    public void append(ApprovalRecoveryAudit record) { audit.save(record); }
}
