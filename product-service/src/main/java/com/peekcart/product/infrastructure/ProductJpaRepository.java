package com.peekcart.product.infrastructure;

import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Product} 엔티티에 대한 Spring Data JPA 리포지터리.
 */
public interface ProductJpaRepository extends JpaRepository<Product, Long> {
    Page<Product> findByCategoryIdAndStatus(Long categoryId, ProductStatus status, Pageable pageable);
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);
}
