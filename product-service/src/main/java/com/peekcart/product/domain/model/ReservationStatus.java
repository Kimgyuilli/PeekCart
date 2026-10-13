package com.peekcart.product.domain.model;

/**
 * 재고 예약 원장 상태 (ADR-0012 D3).
 * <ul>
 *   <li>{@code RESERVED} — 재고 차감 완료 (예약 성공)</li>
 *   <li>{@code CONFIRMED} — 결제 성공으로 예약 확정(commit). 일반 취소는 복구하지 않는다. 새 fence를 거친 예약은 성공 환불로만 복구한다 (ADR-0034)</li>
 *   <li>{@code CANCEL_REQUESTED} — 예약(order.created) 도착 전에 취소가 먼저 온 tombstone. 이후 예약은 차감하지 않는다</li>
 *   <li>{@code RELEASED} — 예약 후 취소/결제실패로 재고 복구 완료</li>
 *   <li>{@code FAILED} — 재고 부족으로 예약 실패</li>
 * </ul>
 */
public enum ReservationStatus {
    RESERVED,
    /** PG 결과가 밝혀질 때까지 일반 취소/만료 회수 금지 (ADR-0034). */
    APPROVAL_HELD,
    CONFIRMED,
    CANCEL_REQUESTED,
    RELEASED,
    FAILED
}
