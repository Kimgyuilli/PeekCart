package com.peekcart.global.outbox.dto;

/**
 * 환불 보상이 필요해진 사유 (ADR-0018 D1). 요청 토픽 2종({@code stock.compensation.requested} ·
 * {@code order.compensation.requested})의 공통 필수 필드다.
 *
 * <p>사유는 <b>발행자가 명시</b>한다 — 토픽으로 사유를 추론하면(“stock 토픽이니 UNRESERVED”)
 * 토픽이 늘어날 때마다 추론 규칙이 갈라진다({@link OrderCancelReason} 과 같은 판단).
 */
public enum CompensationReason {

    /** 결제는 승인됐으나 재고가 확정되지 않았다 (Product 감지, ADR-0018 C1 ①). */
    PAID_BUT_UNRESERVED,

    /** 취소된 주문에 결제 완료가 도착했다 (Order·Payment 감지, ADR-0018 C1 ②③). */
    PAID_BUT_CANCELLED
}
