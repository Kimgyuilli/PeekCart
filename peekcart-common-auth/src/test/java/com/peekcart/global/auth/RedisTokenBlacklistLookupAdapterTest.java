package com.peekcart.global.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * blacklist read-only 어댑터의 fail-closed/miss 시맨틱 단위 회귀 (ADR-0014 D1-c).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisTokenBlacklistLookupAdapter 단위 테스트")
class RedisTokenBlacklistLookupAdapterTest {

    @Mock RedisTemplate<String, String> redisTemplate;
    @InjectMocks RedisTokenBlacklistLookupAdapter adapter;

    private static final String TOKEN = "access.token.value";
    private static final String KEY = "bl:" + TOKEN;

    @Test
    @DisplayName("hit: 키가 존재하면 blacklisted=true")
    void hit_returnsTrue() {
        given(redisTemplate.hasKey(KEY)).willReturn(true);

        assertThat(adapter.isBlacklisted(TOKEN)).isTrue();
    }

    @Test
    @DisplayName("miss: 키가 없으면 blacklisted=false (통과)")
    void miss_returnsFalse() {
        given(redisTemplate.hasKey(KEY)).willReturn(false);

        assertThat(adapter.isBlacklisted(TOKEN)).isFalse();
    }

    @Test
    @DisplayName("Redis 조회 실패: fail-closed 로 blacklisted=true (차단)")
    void redisFailure_failClosed_returnsTrue() {
        given(redisTemplate.hasKey(KEY)).willThrow(new RedisConnectionFailureException("down"));

        assertThat(adapter.isBlacklisted(TOKEN)).isTrue();
    }
}
