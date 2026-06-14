package com.peekcart.global.auth;

/**
 * 토큰 블랙리스트 read-only 조회 추상화 (ADR-0014 D1-c).
 * 검증 모듈(common-auth)·게이트웨이가 소유하는 read 계약. write(등록/그레이스)는 User 전속
 * {@link TokenBlacklistPort}.
 *
 * <p>조회 시맨틱:
 * <ul>
 *   <li>miss(없음) = {@code false} → 통과</li>
 *   <li>Redis 조회 실패 = {@code true} → fail-closed(요청 거부)</li>
 * </ul>
 */
public interface TokenBlacklistLookupPort {

    /** 토큰이 블랙리스트에 등록되어 있는지 확인한다. 조회 실패 시 fail-closed. */
    boolean isBlacklisted(String token);
}
