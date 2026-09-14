package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentApprovalRepositoryImpl implements PaymentApprovalRepository {

    private final PaymentApprovalJpaRepository jpaRepository;

    @Override
    public int insertClaimedIfAbsent(Long orderId, String paymentKey, Long userId, long amount, LocalDateTime now) {
        return jpaRepository.insertClaimedIfAbsent(orderId, paymentKey, userId, amount, now);
    }

    @Override
    public int claimForReconcile(Long orderId, LocalDateTime staleBefore, LocalDateTime now) {
        return jpaRepository.claimForReconcile(orderId, staleBefore, now);
    }

    @Override
    public List<Long> findReconcileCandidates(LocalDateTime staleBefore, int limit) {
        return jpaRepository.findReconcileCandidates(staleBefore, limit);
    }

    @Override
    public int nudgeUnresolvedByPaymentKey(String paymentKey) {
        return jpaRepository.nudgeUnresolvedByPaymentKey(paymentKey);
    }

    @Override
    public Optional<PaymentApproval> findByOrderId(Long orderId) {
        return jpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<PaymentApproval> findByOrderIdForUpdate(Long orderId) {
        return jpaRepository.findByOrderIdForUpdate(orderId);
    }

    @Override
    public long countByStatus(ApprovalStatus status) {
        return jpaRepository.countByStatus(status);
    }

    @Override
    public Optional<LocalDateTime> findOldestRequestedAt(ApprovalStatus status) {
        return jpaRepository.findOldestRequestedAt(status);
    }
}
