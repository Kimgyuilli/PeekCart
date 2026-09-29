package com.peekcart.gateway.auth;

import java.time.Instant;

/**
 * Gateway 가 검증한 액세스 토큰 클레임.
 *
 * <p>사용자 토큰 클레임 타입은 이것 하나뿐이다(ADR-0014 D2-c exit).
 * 다운스트림으로는 이 값 자체가 아니라 이를 서명한 내부 토큰이 전달된다(ADR-0017).
 *
 * @param userId     토큰 subject
 * @param role       사용자 역할(USER/ADMIN)
 * @param familyId   refresh family 식별자. family 클레임이 없는 레거시 토큰은 {@code null} —
 *                   {@code app.gateway.internal-token.require-family-id=true}(기본) 이면 발행이 거부된다
 * @param expiration 만료 시각
 */
public record GatewayClaims(Long userId, String role, String familyId, Instant expiration) {
}
