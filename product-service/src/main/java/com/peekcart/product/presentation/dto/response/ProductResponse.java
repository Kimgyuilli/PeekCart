package com.peekcart.product.presentation.dto.response;

import com.peekcart.product.application.dto.ProductListDto;

/**
 * 상품 목록 응답 DTO.
 */
public record ProductResponse(Long id, String name, long price, String imageUrl, String status) {

    public static ProductResponse from(ProductListDto dto) {
        return new ProductResponse(
                dto.id(),
                dto.name(),
                dto.price(),
                dto.imageUrl(),
                dto.status());
    }
}
