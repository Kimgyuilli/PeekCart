package com.peekcart.product.application.dto;

import com.peekcart.product.domain.model.Product;

/**
 * 상품 목록 캐싱용 DTO.
 * <p>JPA 엔티티({@code Product})를 Redis에 직렬화하면 Hibernate 프록시 문제가 발생하므로,
 * 목록 조회 시 이 record로 변환하여 캐싱한다.
 */
public record ProductListDto(
        Long id,
        String name,
        long price,
        String imageUrl,
        String status
) {
    public static ProductListDto of(Product product) {
        return new ProductListDto(
                product.getId(),
                product.getName(),
                product.getPrice(),
                product.getImageUrl(),
                product.getStatus().name());
    }
}
