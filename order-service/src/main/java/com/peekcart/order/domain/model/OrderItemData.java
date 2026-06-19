package com.peekcart.order.domain.model;

/**
 * 주문 항목 생성에 필요한 데이터. 순환 의존 없이 Order.create()에 전달된다.
 */
public record OrderItemData(Long productId, int quantity, long unitPrice) {
}
