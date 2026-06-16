package com.peekcart.product.domain.repository;

import com.peekcart.product.domain.model.StockReservation;

import java.util.Optional;

/**
 * 재고 예약 원장 리포지터리 인터페이스 (ADR-0012 D3).
 */
public interface StockReservationRepository {

    StockReservation save(StockReservation reservation);

    Optional<StockReservation> findByOrderId(Long orderId);

    /**
     * {@code RESERVED → RELEASED} 원자 CAS. 복구 권한은 이 전이가 1건 성공할 때만 부여된다(double-release 방지).
     *
     * @return 전이된 행 수 (0 또는 1)
     */
    int markReleasedIfReserved(Long orderId);
}
