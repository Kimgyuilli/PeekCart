package com.peekcart.product.domain.repository;

import com.peekcart.product.domain.model.Inventory;

import java.util.Optional;

/**
 * 재고 도메인 리포지터리 인터페이스.
 */
public interface InventoryRepository {
    Inventory save(Inventory inventory);
    Optional<Inventory> findByProductId(Long productId);
}
