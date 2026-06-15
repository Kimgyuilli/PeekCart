package com.peekcart.user.application.dto;

/**
 * 토큰 발급 결과를 담는 Application 레이어 DTO.
 *
 * @param accessToken  발급된 JWT 액세스 토큰
 * @param refreshToken 발급된 리프레시 토큰 값
 */
public record TokenResult(String accessToken, String refreshToken) {
}
