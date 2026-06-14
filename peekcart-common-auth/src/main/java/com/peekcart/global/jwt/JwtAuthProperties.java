package com.peekcart.global.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 서명/검증 단일 설정 계약 (ADR-0014 D1-b).
 * 발급(JwtTokenSigner, root/User)과 검증(JwtTokenVerifier, common-auth)이
 * 동일 {@code app.jwt.*} 외부 설정을 바인딩하여 secret/algorithm 드리프트를 차단한다.
 * 전환기 대칭키(HS256) — 게이트웨이 도입 시 RS256 으로 전환(ADR-0013, ADR-0014 D2-a).
 *
 * @param secret             HS256 대칭키 시크릿
 * @param accessTokenExpiry  액세스 토큰 만료(ms)
 * @param refreshTokenExpiry 리프레시 토큰 만료(ms)
 */
@ConfigurationProperties(prefix = "app.jwt")
public record JwtAuthProperties(String secret, long accessTokenExpiry, long refreshTokenExpiry) {
}
