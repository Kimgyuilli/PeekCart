package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.PaymentCancellation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentCancellationJpaRepository extends JpaRepository<PaymentCancellation, Long> {

    boolean existsByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM PaymentCancellation c WHERE c.orderId = :orderId")
    Optional<PaymentCancellation> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
