package com.peekcart.product.application.dto;

import com.peekcart.product.domain.model.Product;

/**
 * 상품 상세 캐싱용 DTO (재고 제외).
 * <p>재고는 변경 빈도가 달라 <b>TTL 이 다른 별도 캐시</b>에 있다 (ADR-0026 D2 — 상품 30분 /
 * 재고 5초). {@link ProductDetailDto}는 이 DTO + 재고 캐시 값으로 조합된다.
 */
public record ProductInfoDto(
        Long id,
        Long categoryId,
        String categoryName,
        String name,
        String description,
        long price,
        String imageUrl,
        String status
) {
    public static ProductInfoDto of(Product product) {
        return new ProductInfoDto(
                product.getId(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getImageUrl(),
                product.getStatus().name());
    }
}
