package com.peekcart.user.presentation.dto.response;

import com.peekcart.user.domain.model.User;

/**
 * 회원 정보 응답 DTO.
 */
public record UserResponse(Long id, String email, String name, String role) {

    /** 도메인 객체로부터 응답 DTO를 생성한다. */
    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getName(), user.getRole().name());
    }
}
