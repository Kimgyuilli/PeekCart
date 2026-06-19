package com.peekcart.order.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 장바구니 항목 엔티티.
 */
@Entity
@Table(name = "cart_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    private CartItem(Cart cart, Long productId, int quantity) {
        if (quantity < 1) throw new OrderException(ErrorCode.ORD_005);
        this.cart = cart;
        this.productId = productId;
        this.quantity = quantity;
    }

    public static CartItem create(Cart cart, Long productId, int quantity) {
        return new CartItem(cart, productId, quantity);
    }

    public void changeQuantity(int quantity) {
        if (quantity < 1) throw new OrderException(ErrorCode.ORD_005);
        this.quantity = quantity;
    }

    void addQuantity(int delta) {
        if (delta < 1) throw new OrderException(ErrorCode.ORD_005);
        this.quantity += delta;
    }
}
