package com.peekcart.global.outbox.dto;

import java.util.List;

/**
 * 주문 취소 이벤트({@code order.cancelled}) payload. 파티션 키 = {@code orderId}.
 *
 * <p>{@code items}·{@code reason} 은 ADR-0012 D2 가 명문화한 계약이며, 하위호환 필드
 * 추가에 해당한다 — 구 메시지에는 부재하므로 소비자는 필드 없음을 견뎌야 한다.
 *
 * @param orderId     주문 PK (파티션 키)
 * @param orderNumber 주문 번호
 * @param userId      주문 소유자
 * @param items       복구 대상 품목. Product 의 재고 복구는 예약 원장 기반이라 현재 이 필드에 의존하지
 *                    않지만, ADR-0012 D2 가 요구하는 계약이므로 발행 측이 채운다
 * @param reason      취소 사유
 */
public record OrderCancelledPayload(
        Long orderId,
        String orderNumber,
        Long userId,
        List<OrderItemPayload> items,
        OrderCancelReason reason
) {
}
