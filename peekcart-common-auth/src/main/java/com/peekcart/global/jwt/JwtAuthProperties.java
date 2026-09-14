package com.peekcart.global.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 토큰 수명 설정 (ADR-0014 D1-b).
 *
 * <p><b>PR4</b>: 전환기 대칭키 {@code secret} 필드를 제거했다. 발급은 RS256 개인키 단독
 * ({@code JwtTokenSigner} + {@code app.jwt.rs256.*})이고 검증은 Gateway 가 JWKS 로 수행하므로
 * (ADR-0013 D1), 이 레코드가 남기는 것은 <b>만료 시간</b>뿐이다. 아무도 읽지 않는 시크릿 필드를
 * 두면 "아직 대칭키로 서명한다"는 거짓 신호가 된다.
 *
 * @param accessTokenExpiry  액세스 토큰 만료(ms) — family deny TTL 의 상한이기도 하다
 * @param refreshTokenExpiry 리프레시 토큰 만료(ms)
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtAuthProperties(long accessTokenExpiry, long refreshTokenExpiry) {
}
