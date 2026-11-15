package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.PaymentReservationResult;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentReservationResultJpaRepository extends JpaRepository<PaymentReservationResult, Long> {

    boolean existsByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PaymentReservationResult r WHERE r.orderId = :orderId")
    Optional<PaymentReservationResult> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
