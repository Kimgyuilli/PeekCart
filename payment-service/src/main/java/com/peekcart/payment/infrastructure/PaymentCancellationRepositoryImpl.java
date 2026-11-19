package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.PaymentCancellation;
import com.peekcart.payment.domain.repository.PaymentCancellationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class PaymentCancellationRepositoryImpl implements PaymentCancellationRepository {

    private final PaymentCancellationJpaRepository jpaRepository;

    @Override
    public PaymentCancellation save(PaymentCancellation cancellation) {
        return jpaRepository.save(cancellation);
    }

    @Override
    public boolean existsByOrderId(Long orderId) {
        return jpaRepository.existsByOrderId(orderId);
    }

    @Override
    public Optional<PaymentCancellation> findByOrderIdForUpdate(Long orderId) {
        return jpaRepository.findByOrderIdForUpdate(orderId);
    }

    @Override
    public void delete(PaymentCancellation cancellation) {
        jpaRepository.delete(cancellation);
    }
}
