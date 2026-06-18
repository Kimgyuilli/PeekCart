package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.PaymentCancellation;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentCancellationJpaRepository extends JpaRepository<PaymentCancellation, Long> {

    boolean existsByOrderId(Long orderId);

    void deleteByOrderId(Long orderId);
}
