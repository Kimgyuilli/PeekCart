package com.peekcart.product.infrastructure;

import com.peekcart.product.domain.model.StockReservation;
import com.peekcart.product.domain.repository.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link StockReservationRepository}의 JPA 구현체.
 */
@Repository
@RequiredArgsConstructor
public class StockReservationRepositoryImpl implements StockReservationRepository {

    private final StockReservationJpaRepository jpaRepository;

    @Override
    public Optional<StockReservation> findByOrderIdForUpdate(Long orderId) {
        return jpaRepository.findByOrderIdForUpdate(orderId);
    }

    @Override
    public int holdForApproval(Long orderId) {
        return jpaRepository.holdForApproval(orderId, LocalDateTime.now());
    }

    @Override
    public int releaseAfterPaymentFailed(Long orderId) {
        return jpaRepository.releaseAfterPaymentFailed(orderId, LocalDateTime.now());
    }

    @Override
    public int releaseAfterRefund(Long orderId) {
        return jpaRepository.releaseAfterRefund(orderId, LocalDateTime.now());
    }

    @Override
    public StockReservation save(StockReservation reservation) {
        return jpaRepository.save(reservation);
    }

    @Override
    public Optional<StockReservation> findByOrderId(Long orderId) {
        return jpaRepository.findByOrderId(orderId);
    }

    @Override
    public int markReleasedIfReserved(Long orderId) {
        return jpaRepository.markReleasedIfReserved(orderId, LocalDateTime.now());
    }

    @Override
    public int markConfirmedIfReserved(Long orderId) {
        return jpaRepository.markConfirmedIfReserved(orderId, LocalDateTime.now());
    }

    @Override
    public int markCompensatedIfAbsent(Long orderId, LocalDateTime detectedAt) {
        return jpaRepository.markCompensatedIfAbsent(orderId, detectedAt);
    }

    @Override
    public List<StockReservation> findExpiredReserved(LocalDateTime cutoff, int limit) {
        return jpaRepository.findExpiredReserved(cutoff, PageRequest.of(0, limit));
    }
}
