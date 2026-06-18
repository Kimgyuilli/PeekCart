package com.peekcart.product.application.dto;

import com.peekcart.product.domain.model.Product;

/**
 * 상품 상세 캐싱용 DTO (재고 제외).
 * <p>재고는 차감/복구마다 변경되어 캐시 무효화가 빈번하므로 포함하지 않는다.
 * {@link ProductDetailDto}는 이 DTO + 실시간 재고로 조합된다.
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
