package com.peekcart.product.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 카테고리 엔티티. 자기 참조(parent_id)로 계층 구조를 표현한다.
 */
@Entity
@Table(name = "categories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    private Category(String name, Category parent) {
        this.name = name;
        this.parent = parent;
    }

    public static Category create(String name, Category parent) {
        return new Category(name, parent);
    }
}
