package com.peekcart.order.domain.repository;

import java.util.Optional;

/**
 * Order 로컬 가격 캐시 리포지터리 인터페이스 (CQRS ⑤, strangler-2).
 */
public interface ProductPriceCacheRepository {

    /** 캐시된 단가를 조회한다. 미수신 상품이면 {@link Optional#empty()}. */
    Optional<Long> findUnitPrice(Long productId);

    /**
     * 상품이 로컬 캐시에 존재하는지 검증한다 (장바구니 추가 검증용, strangler-4).
     * 캐시 존재 = product.updated 수신/seed 완료된 주문 가능 상품. 미수신이면 false.
     */
    boolean existsByProductId(Long productId);

    /**
     * {@code product.updated} 를 캐시에 반영한다 (upsert).
     * 더 높은 {@code version} 일 때만 적용하여 역순/replay 이벤트의 과거 version 덮어쓰기를 막는다.
     */
    void applyUpdate(Long productId, long unitPrice, long version);
}
