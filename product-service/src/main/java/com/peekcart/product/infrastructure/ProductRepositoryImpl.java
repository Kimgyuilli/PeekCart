package com.peekcart.product.infrastructure;

import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import com.peekcart.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * {@link ProductRepository}의 JPA 구현체.
 */
@Repository
@RequiredArgsConstructor
public class ProductRepositoryImpl implements ProductRepository {

    private final ProductJpaRepository productJpaRepository;

    @Override
    public Product save(Product product) {
        return productJpaRepository.save(product);
    }

    @Override
    public Product saveAndFlush(Product product) {
        return productJpaRepository.saveAndFlush(product);
    }

    @Override
    public Optional<Product> findById(Long id) {
        return productJpaRepository.findById(id);
    }

    @Override
    public Page<Product> findByCategoryIdAndStatus(Long categoryId, ProductStatus status, Pageable pageable) {
        return productJpaRepository.findByCategoryIdAndStatus(categoryId, status, pageable);
    }

    @Override
    public Page<Product> findByStatus(ProductStatus status, Pageable pageable) {
        return productJpaRepository.findByStatus(status, pageable);
    }
}
