package com.peekcart.global.auth;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 공유 Redis 를 경유하는 블랙리스트 read-only 어댑터 (ADR-0014 D1-c).
 * write owner(User)의 {@code bl:<token>} 키를 동일 직렬화로 조회한다.
 * Redis 조회 실패 시 fail-closed(차단)로 처리하여 차단 토큰 누출을 막는다.
 *
 * <p>PR2a 범위: 현 {@code bl:} 키 스킴을 읽되 fail-closed/miss 시맨틱만 이행한다.
 * jti/토큰 hash + {@code auth:blacklist:} namespace 마이그레이션은 User peel(PR2c)로 이연한다.
 */
@Component
@RequiredArgsConstructor
public class RedisTokenBlacklistLookupAdapter implements TokenBlacklistLookupPort {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBlacklistLookupAdapter.class);
    private static final String BLACKLIST_PREFIX = "bl:";

    private final RedisTemplate<String, String> redisTemplate;

    @Override
    public boolean isBlacklisted(String token) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(BLACKLIST_PREFIX + token));
        } catch (RuntimeException e) {
            // fail-closed: 조회 실패 시 차단으로 간주 (ADR-0014 D1-c, ADR-0013 §48 정합)
            log.warn("토큰 블랙리스트 조회 실패 — fail-closed 로 차단", e);
            return true;
        }
    }
}
