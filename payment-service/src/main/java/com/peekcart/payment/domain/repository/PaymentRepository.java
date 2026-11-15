package com.peekcart.payment.domain.repository;

import com.peekcart.payment.domain.model.Payment;

import java.util.Optional;

public interface PaymentRepository {

    Payment save(Payment payment);

    Optional<Payment> findById(Long id);

    Optional<Payment> findByPaymentKey(String paymentKey);

    Optional<Payment> findByOrderId(Long orderId);

    /** 비관적 잠금 조회. 행이 없으면 {@code uk_payments_order_id} 키 구간을 잠가 Payment 생성과 순서를 맞춘다 (D-112). */
    Optional<Payment> findByOrderIdForUpdate(Long orderId);
}
