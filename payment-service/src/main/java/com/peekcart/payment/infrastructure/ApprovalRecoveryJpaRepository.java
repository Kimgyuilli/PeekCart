package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.ApprovalRecovery;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface ApprovalRecoveryJpaRepository extends JpaRepository<ApprovalRecovery, String> {
    Optional<ApprovalRecovery> findFirstByOrderIdOrderByCreatedAtDesc(Long orderId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ApprovalRecovery r where r.id = :id")
    Optional<ApprovalRecovery> findForUpdate(@Param("id") String id);
    @Query("select count(r) > 0 from ApprovalRecovery r where r.orderId = :orderId and r.status not in ('REJECTED', 'COMPLETED')")
    boolean existsActive(@Param("orderId") Long orderId);
}
