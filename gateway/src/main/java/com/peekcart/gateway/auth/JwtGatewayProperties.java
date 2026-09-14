package com.peekcart.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Gateway JWT 검증 설정 (ADR-0013 D1/D3 · 구현 ③ PR3a).
 *
 * <p>ADR-0007: 동작 규약(알고리즘 allow-list·TTL)은 base `application.yml` 에 두고,
 * 환경마다 달라지는 값은 {@code jwksUri} 뿐이라 환경변수 placeholder 로 주입한다.
 *
 * <p><b>PR4</b>: 전환기 HMAC(HS512) fallback 스위치·시크릿을 제거했다 — 발급측(`JwtTokenSigner`)이
 * RS256 단독이 된 시점부터 HS 토큰은 새로 만들어지지 않으므로, 스위치를 남기면 "아직 대칭키를
 * 쓴다"는 거짓 신호이자 alg 혼동 공격의 재활성 스위치로만 남는다(ADR-0013 D1 완료).
 *
 * @param jwksUri              User JWKS 정본 URI. 공개키 소스는 여기 하나뿐(로컬 미러 금지, ADR-0013 D1)
 * @param jwksTimeout          JWKS 조회 타임아웃
 * @param jwksRefreshCooldown  unknown kid 폭주 시 refresh 최소 간격
 * @param jwksRefreshInterval  주기 갱신 간격
 */
@ConfigurationProperties(prefix = "app.gateway.jwt")
public record JwtGatewayProperties(
        String jwksUri,
        Duration jwksTimeout,
        Duration jwksRefreshCooldown,
        Duration jwksRefreshInterval
) {
    public JwtGatewayProperties {
        jwksTimeout = jwksTimeout != null ? jwksTimeout : Duration.ofSeconds(2);
        jwksRefreshCooldown = jwksRefreshCooldown != null ? jwksRefreshCooldown : Duration.ofSeconds(10);
        jwksRefreshInterval = jwksRefreshInterval != null ? jwksRefreshInterval : Duration.ofMinutes(5);
    }
}
