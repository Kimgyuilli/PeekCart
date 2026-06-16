package com.peekcart.product.domain.model;

import com.peekcart.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 도메인 엔티티.
 * 비즈니스 로직(수정, 판매 중단)을 직접 보유한다.
 */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private long price;

    @Column(name = "image_url")
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductStatus status;

    /** 변경 순서 판정용 낙관락 버전 (strangler-2: product.updated 캐시 stale-skip 기준). */
    @Version
    private Long version;

    private Product(Category category, String name, String description, long price, String imageUrl) {
        if (price < 0) throw new IllegalArgumentException("가격은 0 이상이어야 합니다.");
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
        this.status = ProductStatus.ON_SALE;
    }

    public static Product create(Category category, String name, String description, long price, String imageUrl) {
        return new Product(category, name, description, price, imageUrl);
    }

    public void update(Category category, String name, String description, long price, String imageUrl) {
        this.category = category;
        this.name = name;
        this.description = description;
        this.price = price;
        this.imageUrl = imageUrl;
    }

    public void discontinue() {
        this.status = ProductStatus.DISCONTINUED;
    }

    public boolean isOnSale() {
        return this.status == ProductStatus.ON_SALE;
    }
}
