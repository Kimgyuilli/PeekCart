package com.peekcart.product.infrastructure;

import com.peekcart.product.domain.model.Category;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link Category} 엔티티에 대한 Spring Data JPA 리포지터리.
 */
public interface CategoryJpaRepository extends JpaRepository<Category, Long> {
}
