package com.peekcart.user.infrastructure;

import com.peekcart.user.domain.model.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * {@link RefreshToken} 엔티티에 대한 Spring Data JPA 리포지터리.
 */
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshToken, Long> {
    Optional<RefreshToken> findByToken(String token);
    int deleteByToken(String token);
    void deleteByUserId(Long userId);
}
