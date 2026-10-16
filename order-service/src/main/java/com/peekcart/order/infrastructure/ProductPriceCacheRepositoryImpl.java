package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;
import java.util.Collection;
import com.peekcart.order.domain.model.ProductPriceCache;

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
    public void applyUpdate(Long productId, long unitPrice, long version, String productName, String productStatus) {
        jpaRepository.upsertIfNewer(productId, unitPrice, version, productName, productStatus, LocalDateTime.now());
    }
    @Override
    public List<ProductPriceCache> findAllByProductIdIn(Collection<Long> productIds) {
        return jpaRepository.findAllById(productIds);
    }
}
