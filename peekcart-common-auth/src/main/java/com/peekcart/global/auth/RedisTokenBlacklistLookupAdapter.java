package com.peekcart.global.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 공유 Redis 를 경유하는 블랙리스트 read-only 어댑터 (ADR-0014 D1-c · PR2b/U5).
 * Redis 조회 실패 시 fail-closed(차단)로 처리하여 차단 토큰 누출을 막는다.
 *
 * <p><b>전환기 dual-read (U5)</b>: write owner(User)는 신키 {@code auth:blacklist:<sha256hex(token)>}
 * 만 기록하지만, 마이그레이션 이전에 등록된 legacy 키 {@code bl:<token>}(원문)도 access token 최대 TTL
 * 동안 남아 있을 수 있다. 신키만 조회하면 legacy 로 차단된 토큰이 통과(보안 회귀)하므로 <b>신키 우선 +
 * legacy 키 함께 조회</b>한다. legacy 키는 TTL 경과 후 자연 만료되며, 잔존 0 확인 후 dual-read 제거 가능.
 */
@Component
@RequiredArgsConstructor
public class RedisTokenBlacklistLookupAdapter implements TokenBlacklistLookupPort {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBlacklistLookupAdapter.class);
    private static final String BLACKLIST_PREFIX = "auth:blacklist:";   // 신키 (해시)
    private static final String LEGACY_PREFIX = "bl:";                  // 전환기 read-only (원문)

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public boolean isBlacklisted(String token) {
        try {
            if (Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + TokenHasher.sha256Hex(token)))) {
                return true;
            }
            // 전환기 legacy fallback (U5) — 마이그레이션 이전 등록 토큰
            return Boolean.TRUE.equals(redisTemplate.hasKey(LEGACY_PREFIX + token));
        } catch (RuntimeException e) {
            // fail-closed: 조회 실패 시 차단으로 간주 (ADR-0014 D1-c, ADR-0013 §48 정합)
            log.warn("토큰 블랙리스트 조회 실패 — fail-closed 로 차단", e);
            return true;
        }
    }
}
