package com.peekcart.product.domain.repository;

import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.Optional;

/**
 * 상품 도메인 리포지터리 인터페이스.
 */
public interface ProductRepository {
    Product save(Product product);

    /**
     * 저장 후 즉시 flush 하여 {@code @Version} 등 DB 반영 값을 동기화한다.
     * {@code product.updated} 발행 시 증가된 version 을 읽기 위해 사용한다 (strangler-2).
     */
    Product saveAndFlush(Product product);

    Optional<Product> findById(Long id);
    Page<Product> findByCategoryIdAndStatus(Long categoryId, ProductStatus status, Pageable pageable);
    Page<Product> findByStatus(ProductStatus status, Pageable pageable);
}
