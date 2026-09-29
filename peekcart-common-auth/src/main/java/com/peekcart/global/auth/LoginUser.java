package com.peekcart.global.auth;

/**
 * 인증된 사용자의 신원 컨텍스트 객체(header-trust, ADR-0013 D3).
 * Gateway 가 검증 후 주입한 {@code X-User-Id}/{@code X-User-Role}/{@code X-User-Family-Id} 를
 * {@link LoginUserArgumentResolver}가 {@code SecurityContext}에서 추출해 컨트롤러 파라미터로 주입한다.
 *
 * @param userId   토큰 소유자 ID
 * @param role     사용자 역할("USER"/"ADMIN") — {@code ROLE_} 접두사 없는 값
 * @param familyId 리프레시 토큰 family 식별자. family 클레임이 없는 레거시 토큰은 {@code null}
 *                 (family deny 미기록 — access TTL 까지 bounded).
 */
public record LoginUser(Long userId, String role, String familyId) {
}
