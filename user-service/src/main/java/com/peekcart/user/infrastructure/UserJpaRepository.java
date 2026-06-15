package com.peekcart.user.infrastructure;

import com.peekcart.user.domain.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@link User} 엔티티에 대한 Spring Data JPA 리포지터리.
 */
public interface UserJpaRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
}
