package com.peekcart.product.infrastructure;

import com.peekcart.product.domain.model.Inventory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@link Inventory} 엔티티에 대한 Spring Data JPA 리포지터리.
 */
public interface InventoryJpaRepository extends JpaRepository<Inventory, Long> {
    Optional<Inventory> findByProductId(Long productId);
}
