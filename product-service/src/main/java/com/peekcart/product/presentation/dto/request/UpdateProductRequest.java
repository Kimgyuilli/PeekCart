package com.peekcart.product.presentation.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 상품 수정 요청 DTO.
 */
public record UpdateProductRequest(
        @NotNull Long categoryId,
        @NotBlank String name,
        String description,
        @NotNull @Min(0) Long price,
        String imageUrl
) {}
