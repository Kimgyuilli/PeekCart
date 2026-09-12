package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentJpaRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByOrderId(Long orderId);

    /**
     * replay 적격성 판정용 <b>비관적 잠금</b> 조회 (④-c-2b-4a P19 · 계획 리뷰 3R #6).
     *
     * <p>{@code markReadyForPayment} 는 상태 가드 없이 flag 와 lease 를 덮으므로, 판정과 claim 사이의
     * 상태 변화를 막지 않으면 정책이 막으려던 재적용이 그대로 성립한다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Payment p WHERE p.orderId = :orderId")
    Optional<Payment> findByOrderIdForUpdate(@Param("orderId") Long orderId);
}
