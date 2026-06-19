package com.peekcart.order.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 항목 엔티티. 주문 시점 가격을 스냅샷으로 보유한다.
 * 패키지 내부에서만 생성할 수 있다 (Order.create()를 통해 생성).
 */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    OrderItem(Order order, Long productId, int quantity, long unitPrice) {
        if (quantity < 1) throw new OrderException(ErrorCode.ORD_005);
        if (unitPrice < 0) throw new IllegalArgumentException("단가는 0 이상이어야 합니다.");
        this.order = order;
        this.productId = productId;
        this.quantity = quantity;
        this.unitPrice = unitPrice;
    }

    public long getSubtotal() {
        return unitPrice * quantity;
    }
}
