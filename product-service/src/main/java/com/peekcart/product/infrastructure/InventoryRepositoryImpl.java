package com.peekcart.product.infrastructure;

import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link InventoryRepository}의 JPA 구현체.
 */
@Repository
@RequiredArgsConstructor
public class InventoryRepositoryImpl implements InventoryRepository {

    private final InventoryJpaRepository inventoryJpaRepository;

    @Override
    public Inventory save(Inventory inventory) {
        return inventoryJpaRepository.save(inventory);
    }

    @Override
    public Optional<Inventory> findByProductId(Long productId) {
        return inventoryJpaRepository.findByProductId(productId);
    }
}
