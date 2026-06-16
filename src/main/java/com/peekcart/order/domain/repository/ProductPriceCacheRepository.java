package com.peekcart.order.domain.repository;

import java.util.Optional;

/**
 * Order 로컬 가격 캐시 리포지터리 인터페이스 (CQRS ⑤, strangler-2).
 */
public interface ProductPriceCacheRepository {

    /** 캐시된 단가를 조회한다. 미수신 상품이면 {@link Optional#empty()}. */
    Optional<Long> findUnitPrice(Long productId);

    /**
     * {@code product.updated} 를 캐시에 반영한다 (upsert).
     * 더 높은 {@code version} 일 때만 적용하여 역순/replay 이벤트의 과거 version 덮어쓰기를 막는다.
     */
    void applyUpdate(Long productId, long unitPrice, long version);
}
