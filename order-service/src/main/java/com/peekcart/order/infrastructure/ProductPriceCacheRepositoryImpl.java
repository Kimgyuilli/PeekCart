package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link ProductPriceCacheRepository}의 JPA 구현체.
 */
@Repository
@RequiredArgsConstructor
public class ProductPriceCacheRepositoryImpl implements ProductPriceCacheRepository {

    private final ProductPriceCacheJpaRepository jpaRepository;

    @Override
    public Optional<Long> findUnitPrice(Long productId) {
        return jpaRepository.findUnitPriceByProductId(productId);
    }

    @Override
    public boolean existsByProductId(Long productId) {
        return jpaRepository.existsById(productId);
    }

    @Override
    public void applyUpdate(Long productId, long unitPrice, long version) {
        jpaRepository.upsertIfNewer(productId, unitPrice, version, LocalDateTime.now());
    }
}
