package com.peekcart.gateway.ratelimit;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.ratelimit.AbstractRateLimiter;
import org.springframework.cloud.gateway.support.ConfigurationService;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>fail-closed</b> Redis rate limiter (ADR-0013 D3 · 계획 P13 · GW-2 c2:3/c3:1).
 *
 * <p><b>왜 기본 {@code RedisRateLimiter} 를 쓰지 않는가</b>: Spring Cloud Gateway 의 기본 구현은
 * Redis/Lua 오류를 내부에서 잡아 {@code allowed=true} 로 변환한다(fail-<b>open</b>). 즉 Redis 가 죽으면
 * rate limit 이 통째로 사라진다 — ADR-0013 D3 의 "Redis 장애 시 fail-closed" 와 정반대다.
 * {@code deny-empty-key} 는 KeyResolver 가 빈 키를 줄 때만 동작하므로 이 문제를 덮지 못한다.
 *
 * <p><b>알고리즘</b>: 고정 윈도우 카운터(INCR + 최초 1회 EXPIRE). 기본 구현의 token bucket 보다 단순하고
 * 경계에서 최대 2배 버스트를 허용하지만, 본 PR 의 목적은 정밀한 셰이핑이 아니라 <b>남용 차단 + 장애 시
 * 안전한 거부</b>다. 정밀 셰이핑이 필요해지면 Lua 기반 token bucket 으로 교체한다.
 *
 * <p>Redis 오류는 삼키지 않고 {@link RateLimiterUnavailableException} 으로 전파하며, 인증 필터가 이를
 * 503 으로 매핑한다.
 */
@Component
@Primary  // SCG 자동설정의 redisRateLimiter(fail-OPEN)보다 우선 — 기본값도 fail-closed 여야 안전하다
public class FailClosedRedisRateLimiter extends AbstractRateLimiter<FailClosedRedisRateLimiter.Config> {

    public static final String CONFIGURATION_PROPERTY_NAME = "fail-closed-rate-limiter";
    private static final String KEY_PREFIX = "gw:rl:";

    /** S9 — 한도 초과(429) 전용 카운터 이름. 판정 불가(503)는 {@code auth.failure} 소관이다. */
    static final String RATELIMIT_REJECTED = "auth.ratelimit.rejected";

    private final ReactiveStringRedisTemplate redis;
    private final MeterRegistry registry;
    /** route 별 카운터. routeId 는 라우트 정의에서만 오므로 태그 카디널리티가 설정으로 유계다. */
    private final Map<String, Counter> rejectedByRoute = new ConcurrentHashMap<>();

    public FailClosedRedisRateLimiter(ReactiveStringRedisTemplate redis,
                                      ConfigurationService configurationService,
                                      MeterRegistry registry) {
        super(Config.class, CONFIGURATION_PROPERTY_NAME, configurationService);
        this.redis = redis;
        this.registry = registry;
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        Config config = loadConfiguration(routeId);
        int limit = config.getBurstCapacity();
        Duration window = Duration.ofSeconds(config.getWindowSeconds());

        // 윈도우 경계를 키에 넣어 만료 의존 없이 자연 롤오버시킨다.
        long bucket = System.currentTimeMillis() / window.toMillis();
        String key = KEY_PREFIX + routeId + ":" + id + ":" + bucket;

        return redis.opsForValue().increment(key)
                .flatMap(count -> {
                    Mono<Boolean> ensureTtl = count == 1L
                            ? redis.expire(key, window.multipliedBy(2))
                            : Mono.just(true);
                    return ensureTtl.thenReturn(count);
                })
                .map(count -> {
                    boolean allowed = count <= limit;
                    if (!allowed) {
                        // 한도 초과만 센다(S9 · ADR-0024 D4). Redis 장애는 아래 onErrorMap 으로 빠지므로
                        // 이 지점에 오지 않는다 — 둘을 한 카운터로 합치면 "남용 급증" 과 "백엔드 장애" 가
                        // 같은 숫자가 되어 대응이 갈린다.
                        rejected(routeId).increment();
                    }
                    long remaining = Math.max(0, limit - count);
                    Map<String, String> headers = new HashMap<>();
                    headers.put("X-RateLimit-Limit", String.valueOf(limit));
                    headers.put("X-RateLimit-Remaining", String.valueOf(remaining));
                    return new Response(allowed, headers);
                })
                // fail-closed: Redis 장애를 통과로 바꾸지 않는다. 429 가 아니라 503 으로 분류되도록
                // 전용 예외로 전파한다(계획 P12 응답 행렬).
                .onErrorMap(e -> !(e instanceof RateLimiterUnavailableException),
                        e -> new RateLimiterUnavailableException(
                                "rate limiter Redis 조회 실패 (route=" + routeId + ")", e));
    }

    private Counter rejected(String routeId) {
        return rejectedByRoute.computeIfAbsent(routeId, id -> Counter.builder(RATELIMIT_REJECTED)
                .tag("route", id)
                .description("rate limit 한도 초과로 거부된 요청 (429)")
                .register(registry));
    }

    /** 라우트 args 로 바인딩된 설정. 미지정 라우트는 보수적인 기본값을 쓴다. */
    private Config loadConfiguration(String routeId) {
        Config config = getConfig().get(routeId);
        return config != null ? config : new Config();
    }

    /** 라우트별 설정 — yml 의 `fail-closed-rate-limiter.*` args 로 바인딩된다. */
    public static class Config {
        /** 윈도우 내 허용 요청 수. */
        private int burstCapacity = 20;
        /** 윈도우 길이(초). */
        private int windowSeconds = 1;

        public int getBurstCapacity() {
            return burstCapacity;
        }

        public Config setBurstCapacity(int burstCapacity) {
            this.burstCapacity = burstCapacity;
            return this;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public Config setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
            return this;
        }
    }
}
