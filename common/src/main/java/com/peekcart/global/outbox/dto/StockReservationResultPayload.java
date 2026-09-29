package com.peekcart.global.outbox.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 재고 예약 결과 이벤트({@code stock.reservation.result}) payload (ADR-0012 §50, D3).
 * Product 가 발행하고 Order/Payment 가 소비한다. 파티션 키 = {@code orderId}.
 *
 * @param orderId              주문 PK (파티션 키)
 * @param reserved             예약 성공 여부 (true = 재고 차감 완료)
 * @param items                예약 대상 품목
 * @param reason               실패 사유 (예: {@code OUT_OF_STOCK}, {@code CANCELLED}). 성공 시 null
 * @param decidedAt            결정 시각
 * @param reservationExpiresAt 예약 lease 만료 시각. Product 가 부여하는 단일 출처로, Order 는 이 시각에
 *                             자기 주문을 취소하고 Payment 는 이후 승인을 거부한다. 실패 시 null.
 *                             하위호환 필드 추가(ADR-0012 D2) — 구 메시지에는 부재하며 소비자는 null 로 취급한다
 */
public record StockReservationResultPayload(
        Long orderId,
        boolean reserved,
        List<ReservedItemPayload> items,
        String reason,
        LocalDateTime decidedAt,
        LocalDateTime reservationExpiresAt
) {
}
