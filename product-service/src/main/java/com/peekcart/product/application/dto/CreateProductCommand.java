package com.peekcart.product.application.dto;

/**
 * 상품 등록 Application Command DTO.
 */
public record CreateProductCommand(
        Long categoryId,
        String name,
        String description,
        long price,
        String imageUrl,
        int stock
) {}
