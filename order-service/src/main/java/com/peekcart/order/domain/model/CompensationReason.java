package com.peekcart.order.domain.model;

/**
 * 보상이 필요한 사유.
 */
public enum CompensationReason {

    /** 취소된 주문에 결제 완료가 도착했다 — 과금은 성립했으나 주문은 종결. 환불 필요. */
    PAID_BUT_CANCELLED
}
