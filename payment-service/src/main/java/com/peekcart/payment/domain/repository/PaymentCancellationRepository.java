package com.peekcart.payment.domain.repository;

import com.peekcart.payment.domain.model.PaymentCancellation;

public interface PaymentCancellationRepository {

    PaymentCancellation save(PaymentCancellation cancellation);

    boolean existsByOrderId(Long orderId);

    void deleteByOrderId(Long orderId);
}
