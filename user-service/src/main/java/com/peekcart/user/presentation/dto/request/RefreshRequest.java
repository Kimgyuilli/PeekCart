package com.peekcart.user.presentation.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 토큰 재발급 요청 DTO.
 */
public record RefreshRequest(
        @NotBlank String refreshToken
) {}
