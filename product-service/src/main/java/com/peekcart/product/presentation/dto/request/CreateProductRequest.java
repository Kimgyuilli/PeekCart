package com.peekcart.product.presentation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 상품 등록 요청 DTO.
 */
public record CreateProductRequest(
        @NotNull Long categoryId,
        @NotBlank String name,
        String description,
        @NotNull @Min(0) Long price,
        String imageUrl,
        @NotNull @Min(0) Integer stock
) {}
