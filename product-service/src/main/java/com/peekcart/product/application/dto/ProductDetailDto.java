package com.peekcart.product.application.dto;

import com.peekcart.product.domain.model.Product;

/**
 * 상품 상세 조회 결과를 담는 Application 레이어 DTO.
 * 트랜잭션 경계 안에서 엔티티로부터 데이터를 추출하여 LAZY 프록시 노출을 방지한다.
 */
public record ProductDetailDto(
        Long id,
        Long categoryId,
        String categoryName,
        String name,
        String description,
        long price,
        String imageUrl,
        String status,
        int stock
) {
    public static ProductDetailDto of(Product product, int stock) {
        return new ProductDetailDto(
                product.getId(),
                product.getCategory().getId(),
                product.getCategory().getName(),
                product.getName(),
                product.getDescription(),
                product.getPrice(),
                product.getImageUrl(),
                product.getStatus().name(),
                stock
        );
    }
}
