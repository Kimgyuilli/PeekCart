package com.peekcart.user.presentation.dto.response;

import com.peekcart.user.application.dto.TokenResult;

/**
 * 액세스 토큰과 리프레시 토큰을 담는 응답 DTO.
 */
public record TokenResponse(String accessToken, String refreshToken) {

    /** Application 레이어 결과로부터 응답 DTO를 생성한다. */
    public static TokenResponse from(TokenResult result) {
        return new TokenResponse(result.accessToken(), result.refreshToken());
    }
}
