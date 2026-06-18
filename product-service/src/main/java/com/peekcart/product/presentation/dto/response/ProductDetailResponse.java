package com.peekcart.product.presentation.dto.response;

import com.peekcart.product.application.dto.ProductDetailDto;

/**
 * 상품 상세 응답 DTO.
 */
public record ProductDetailResponse(
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

    public static ProductDetailResponse from(ProductDetailDto dto) {
        return new ProductDetailResponse(
                dto.id(),
                dto.categoryId(),
                dto.categoryName(),
                dto.name(),
                dto.description(),
                dto.price(),
                dto.imageUrl(),
                dto.status(),
                dto.stock()
        );
    }
}
