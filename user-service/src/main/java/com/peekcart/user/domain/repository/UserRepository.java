package com.peekcart.user.domain.repository;

import com.peekcart.user.domain.model.User;

import java.util.Optional;

/**
 * 회원 도메인 리포지터리 인터페이스.
 * 구현체는 infrastructure 레이어의 {@code UserRepositoryImpl}이 담당한다.
 */
public interface UserRepository {
    /** 이메일로 회원을 조회한다. */
    Optional<User> findByEmail(String email);
    /** 이메일 중복 여부를 확인한다. */
    boolean existsByEmail(String email);
    /** 회원을 저장하고 저장된 엔티티를 반환한다. */
    User save(User user);
    /** PK로 회원을 조회한다. */
    Optional<User> findById(Long id);
}
