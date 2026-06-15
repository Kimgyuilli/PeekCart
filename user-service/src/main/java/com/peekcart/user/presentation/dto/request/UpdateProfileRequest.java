package com.peekcart.user.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 회원 프로필 수정 요청 DTO.
 */
public record UpdateProfileRequest(
        @NotBlank String name
) {}
