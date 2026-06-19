package com.peekcart.order.domain.repository;

import com.peekcart.order.domain.model.Cart;

import java.util.Optional;

/**
 * 장바구니 도메인 리포지터리 인터페이스.
 */
public interface CartRepository {
    Cart save(Cart cart);
    Optional<Cart> findByUserId(Long userId);
}
