package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.PaymentReservationResult;
import com.peekcart.payment.domain.repository.PaymentReservationResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentReservationResultRepositoryImpl implements PaymentReservationResultRepository {

    private final PaymentReservationResultJpaRepository jpaRepository;

    @Override
    public PaymentReservationResult save(PaymentReservationResult result) {
        return jpaRepository.save(result);
    }

    @Override
    public boolean existsByOrderId(Long orderId) {
        return jpaRepository.existsByOrderId(orderId);
    }

    @Override
    public Optional<PaymentReservationResult> findByOrderIdForUpdate(Long orderId) {
        return jpaRepository.findByOrderIdForUpdate(orderId);
    }

    @Override
    public void delete(PaymentReservationResult result) {
        jpaRepository.delete(result);
    }
}
