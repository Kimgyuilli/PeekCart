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

    /**
     * {@code RESERVED → CONFIRMED} 원자 CAS (strangler-3). 확정(commit) 은 이 전이가 1건 성공할 때만 부여된다.
     * CONFIRMED 는 종결 상태라 이후 {@link #markReleasedIfReserved}(RESERVED 조건) 가 자연히 no-op 이 된다.
     *
     * @return 전이된 행 수 (0 또는 1)
     */
    int markConfirmedIfReserved(Long orderId);

    /**
     * commit-실패 보상 1회성 marker 원자 CAS. {@code compensated_at} 이 비어있을 때만 채워 1건을 반환한다.
     * orderId 기준 멱등 — DLQ 재발행(새 eventId) 으로 confirm 이 재실행돼도 보상 알림이 중복 발송되지 않는다.
     *
     * @return 마킹된 행 수 (0 또는 1; 1 = 최초 보상)
     */
    int markCompensatedIfAbsent(Long orderId);
}
