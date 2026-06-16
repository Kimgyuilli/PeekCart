package com.peekcart.product.domain.model;

/**
 * 재고 예약 원장 상태 (ADR-0012 D3, strangler-1).
 * <ul>
 *   <li>{@code RESERVED} — 재고 차감 완료 (예약 성공)</li>
 *   <li>{@code CANCEL_REQUESTED} — 예약(order.created) 도착 전에 취소가 먼저 온 tombstone. 이후 예약은 차감하지 않는다</li>
 *   <li>{@code RELEASED} — 예약 후 취소/결제실패로 재고 복구 완료</li>
 *   <li>{@code FAILED} — 재고 부족으로 예약 실패</li>
 * </ul>
 */
public enum ReservationStatus {
    RESERVED,
    CANCEL_REQUESTED,
    RELEASED,
    FAILED
}
