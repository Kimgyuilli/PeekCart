package com.peekcart.gateway.ratelimit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.ReactiveValueOperations;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * fail-closed rate limiter 계약 회귀 (ADR-0013 D3 · GW-2 c2:3/c3:1).
 *
 * <p>핵심: <b>Redis 장애를 통과로 바꾸지 않는다</b>. SCG 기본 {@code RedisRateLimiter} 는 오류를 삼키고
 * {@code allowed=true} 를 반환해 장애 시 rate limit 이 통째로 사라지는데, 그 동작을 여기서 배제한다.
 */
@DisplayName("FailClosedRedisRateLimiter — 한도 판정 · Redis 장애 fail-closed")
class FailClosedRedisRateLimiterTest {

    private ReactiveStringRedisTemplate redis;
    private ReactiveValueOperations<String, String> valueOps;
    private FailClosedRedisRateLimiter limiter;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(ReactiveStringRedisTemplate.class);
        valueOps = mock(ReactiveValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        lenient().when(redis.expire(anyString(), any(Duration.class))).thenReturn(Mono.just(true));

        ConfigurationService configurationService =
                new ConfigurationService(null, () -> null, () -> null);
        meterRegistry = new SimpleMeterRegistry();
        limiter = new FailClosedRedisRateLimiter(redis, configurationService, meterRegistry);
        // 라우트 설정 미등록 → Config 기본값(burstCapacity=20, window=1s)
    }

    @Test
    @DisplayName("한도 이내 → allowed=true, 잔여 헤더 노출")
    void withinLimit_allowed() {
        when(valueOps.increment(anyString())).thenReturn(Mono.just(1L));

        StepVerifier.create(limiter.isAllowed("route-a", "user:1"))
                .assertNext(r -> {
                    assertThat(r.isAllowed()).isTrue();
                    assertThat(r.getHeaders()).containsKey("X-RateLimit-Remaining");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("한도 초과 → allowed=false (429 는 RequestRateLimiter 가 부여)")
    void overLimit_denied() {
        when(valueOps.increment(anyString())).thenReturn(Mono.just(21L));

        StepVerifier.create(limiter.isAllowed("route-a", "user:1"))
                .assertNext(r -> {
                    assertThat(r.isAllowed()).isFalse();
                    assertThat(r.getHeaders().get("X-RateLimit-Remaining")).isEqualTo("0");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("Redis 장애 → 통과시키지 않고 RateLimiterUnavailable 전파 (fail-closed, 503 매핑용)")
    void redisFailure_failsClosed() {
        when(valueOps.increment(anyString()))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));

        StepVerifier.create(limiter.isAllowed("route-a", "user:1"))
                .expectError(RateLimiterUnavailableException.class)
                .verify();
    }

    @Test
    @DisplayName("첫 요청에만 TTL 을 건다 (윈도우 연장 방지)")
    void setsTtlOnlyOnFirstIncrement() {
        when(valueOps.increment(anyString())).thenReturn(Mono.just(1L));
        StepVerifier.create(limiter.isAllowed("route-a", "user:1")).expectNextCount(1).verifyComplete();
        org.mockito.Mockito.verify(redis).expire(anyString(), any(Duration.class));

        org.mockito.Mockito.clearInvocations(redis);
        when(valueOps.increment(anyString())).thenReturn(Mono.just(2L));
        StepVerifier.create(limiter.isAllowed("route-a", "user:1")).expectNextCount(1).verifyComplete();
        org.mockito.Mockito.verify(redis, org.mockito.Mockito.never())
                .expire(anyString(), any(Duration.class));
    }


    @Test
    @DisplayName("S9 — 한도 초과 1건마다 auth.ratelimit.rejected{route} +1")
    void overLimit_incrementsRejectedCounter() {
        when(valueOps.increment(anyString())).thenReturn(Mono.just(21L));

        StepVerifier.create(limiter.isAllowed("route-a", "user:1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(limiter.isAllowed("route-a", "user:2")).expectNextCount(1).verifyComplete();

        assertThat(meterRegistry.get("auth.ratelimit.rejected").tag("route", "route-a").counter().count())
                .isEqualTo(2.0);
    }

    @Test
    @DisplayName("S9 — 한도 이내 통과는 카운터를 올리지 않는다")
    void withinLimit_incrementsNothing() {
        when(valueOps.increment(anyString())).thenReturn(Mono.just(1L));

        StepVerifier.create(limiter.isAllowed("route-a", "user:1")).expectNextCount(1).verifyComplete();

        assertThat(meterRegistry.find("auth.ratelimit.rejected").counters()).isEmpty();
    }

    @Test
    @DisplayName("S9 — Redis 장애(판정 불가)는 429 카운터를 올리지 않는다")
    void redisFailure_doesNotIncrementRejectedCounter() {
        // 한도 초과(남용)와 백엔드 장애를 한 숫자로 합치면 대응이 갈린다 — 전자는 클라이언트,
        // 후자는 우리 인프라다. 장애는 auth.failure{reason=rate_limiter_unavailable} 소관이다.
        when(valueOps.increment(anyString()))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));

        StepVerifier.create(limiter.isAllowed("route-a", "user:1"))
                .expectError(RateLimiterUnavailableException.class)
                .verify();

        assertThat(meterRegistry.find("auth.ratelimit.rejected").counters()).isEmpty();
    }

    @Test
    @DisplayName("S9 — route 별로 카운터가 분리된다 (어느 라우트가 맞는지 보여야 한다)")
    void countersAreSeparatedByRoute() {
        when(valueOps.increment(anyString())).thenReturn(Mono.just(21L));

        StepVerifier.create(limiter.isAllowed("route-a", "user:1")).expectNextCount(1).verifyComplete();
        StepVerifier.create(limiter.isAllowed("route-b", "user:1")).expectNextCount(1).verifyComplete();

        assertThat(meterRegistry.get("auth.ratelimit.rejected").tag("route", "route-a").counter().count())
                .isEqualTo(1.0);
        assertThat(meterRegistry.get("auth.ratelimit.rejected").tag("route", "route-b").counter().count())
                .isEqualTo(1.0);
    }
}
