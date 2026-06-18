package com.peekcart.product.domain.repository;

import com.peekcart.product.domain.model.Category;

import java.util.Optional;

/**
 * 카테고리 도메인 리포지터리 인터페이스.
 */
public interface CategoryRepository {
    Optional<Category> findById(Long id);
}
