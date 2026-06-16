package com.peekcart.global.outbox.dto;

/**
 * 재고 예약 결과 이벤트({@code stock.reservation.result})의 품목 단위 payload (ADR-0012 §50).
 */
public record ReservedItemPayload(
        Long productId,
        int quantity
) {
}
