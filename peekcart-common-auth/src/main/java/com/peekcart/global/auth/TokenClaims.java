package com.peekcart.global.auth;

import java.time.Instant;

/**
 * 액세스 토큰에서 추출한 클레임 정보.
 * jjwt {@code Claims} 타입을 Application/Infrastructure 레이어에 노출하지 않기 위한 캡슐화.
 *
 * @param userId     토큰 소유자 ID
 * @param role       사용자 역할 (예: "USER", "ADMIN")
 * @param expiration 토큰 만료 시각
 */
public record TokenClaims(Long userId, String role, Instant expiration) {
}
