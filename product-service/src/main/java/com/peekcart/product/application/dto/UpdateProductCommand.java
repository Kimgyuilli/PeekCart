package com.peekcart.product.application.dto;

/**
 * 상품 수정 Application Command DTO.
 */
public record UpdateProductCommand(
        Long categoryId,
        String name,
        String description,
        long price,
        String imageUrl
) {}
