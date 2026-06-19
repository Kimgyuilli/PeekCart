package com.peekcart.order.domain.model;

import com.peekcart.global.entity.BaseTimeEntity;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 장바구니 엔티티 (애그리거트 루트).
 * 항목 추가 시 동일 상품이 있으면 수량을 합산한다.
 */
@Entity
@Table(name = "carts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Cart extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @OneToMany(mappedBy = "cart", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<CartItem> items = new ArrayList<>();

    private Cart(Long userId) {
        this.userId = userId;
    }

    public static Cart create(Long userId) {
        return new Cart(userId);
    }

    public void addItem(Long productId, int quantity) {
        items.stream()
                .filter(item -> item.getProductId().equals(productId))
                .findFirst()
                .ifPresentOrElse(
                        item -> item.addQuantity(quantity),
                        () -> items.add(CartItem.create(this, productId, quantity))
                );
    }

    public void updateItemQuantity(Long cartItemId, int quantity) {
        CartItem item = findItem(cartItemId);
        item.changeQuantity(quantity);
    }

    public void removeItem(Long cartItemId) {
        CartItem item = findItem(cartItemId);
        items.remove(item);
    }

    public void clear() {
        items.clear();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    private CartItem findItem(Long cartItemId) {
        return items.stream()
                .filter(item -> item.getId().equals(cartItemId))
                .findFirst()
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_006));
    }
}
