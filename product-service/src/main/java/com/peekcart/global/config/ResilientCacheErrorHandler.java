package com.peekcart.global.config;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandInterruptedException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.serializer.SerializationException;

import java.io.IOException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * Redis 장애 시 캐시 경로를 fail-open 으로 흘리는 {@link CacheErrorHandler} (L-006).
 *
 * <p><b>왜 fail-open 인가</b>: Redis 는 5서비스 공유 인프라다. 기본
 * {@code SimpleCacheErrorHandler} 는 캐시 예외를 그대로 되던지므로, Redis 가 죽으면
 * DB 는 멀쩡한데도 상품 조회 API 전체가 5xx 로 떨어진다. 캐시는 가용성 단일점이 아니어야 한다.
 *
 * <p><b>왜 gateway 는 정반대인가</b>: {@code FailClosedRedisRateLimiter} 는 Redis 장애 시
 * <b>fail-closed</b> 로 거부한다(ADR-0013 D3). rate limit 은 <i>보호 장치</i>라 유실되면
 * 남용이 무제한 통과하지만, 조회 캐시는 <i>가속 장치</i>라 유실돼도 DB 라는 정답이 남는다.
 * 같은 Redis 를 두 정책으로 다루는 이유가 이 차이다.
 *
 * <p><b>콜백별 결과가 다르다</b> — 그래서 정책도 다르다.
 * <ul>
 *   <li><b>get 실패</b>: {@code CacheInterceptor} 가 캐시 미스로 간주해 타깃 메서드를 실행한다
 *       → DB 조회 → 정상 응답. 이것이 L-006 이 원하는 동작이다.
 *   <li><b>put 실패</b>: 응답은 이미 산출됐으므로 정확성은 유지된다. 다만 캐시가 채워지지 않아
 *       <b>모든 후속 요청이 DB 를 다시 친다</b> — Redis 장애가 DB 부하로 전이되고, 동시 요청
 *       하에서는 cache stampede 로 커넥션 풀을 압박한다. 무해하지 않다. 완화 기구는 후속(M7).
 *   <li><b>evict/clear 실패</b>: DB 는 커밋되고 무효화만 실패한다 → Redis 복구 후에도 TTL
 *       (상세 30m / 목록 10m) 만료 전까지 <b>stale 가격·상태가 서빙</b>된다. 판매중단 상품이
 *       최대 30분 노출될 수 있다. 조용히 넘기면 아무도 모르므로 <b>WARN + 메트릭</b>으로 남긴다.
 * </ul>
 *
 * <p><b>무엇이든 삼키지는 않는다</b>: fail-open 은 <i>가용성</i> 장애에 대한 정책이다. 직렬화 불일치,
 * 잘못된 명령/ACL, 캐시 구현 결함 같은 <i>정합성</i> 오류까지 삼키면 "Redis 가 죽었다"와
 * "캐시가 고장 났다"가 같은 신호로 뭉개져, 배포 후 모든 요청이 조용히 DB 로 가는 상태를
 * 아무도 원인 규명하지 못한다. 연결 실패·명령 타임아웃 계열만 허용하고 나머지는
 * 기본 {@code SimpleCacheErrorHandler} 처럼 <b>되던진다</b>.
 *
 * <p>복구 방침(TTL 만료 대기)과 수동 키 삭제 절차는 {@code docs/runbooks/redis-cache-fallback.md}.
 */
@Slf4j
public class ResilientCacheErrorHandler implements CacheErrorHandler {

    /** Micrometer {@code cache.fallback} → Prometheus {@code cache_fallback_total}. */
    static final String METRIC_NAME = "cache.fallback";

    /** 지연 해석 — 이유는 {@code CacheConfig#meterRegistryProvider} javadoc 참고. */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;

    public ResilientCacheErrorHandler(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
    }

    /**
     * <b>가용성 장애가 아님이 확실한</b> 예외 — 허용 목록보다 먼저 본다.
     * <p>{@link RedisCommandExecutionException} 은 서버가 명령을 <b>받아서 거부</b>한 경우다
     * (문법 오류·ACL {@code NOPERM}·{@code OOM}). 연결은 멀쩡하므로 fail-open 대상이 아니다.
     * {@link SerializationException} 은 캐시 값의 타입 불일치 — 배포 사고이지 인프라 장애가 아니다.
     * {@link RedisCommandInterruptedException} 은 스레드 interrupt 로 명령이 중단된 것이라
     * Redis 상태와 무관하다 — 삼키면 취소된 요청이 DB 조회를 계속한다.
     * 이들은 Lettuce 예외 계층에서 가용성 예외와 형제라 <b>allow 보다 먼저</b> 봐야 한다.
     */
    private static final List<Class<? extends Throwable>> NEVER_SWALLOWED = List.of(
            RedisCommandExecutionException.class,
            RedisCommandInterruptedException.class,
            SerializationException.class);

    /**
     * 가용성 장애로 인정하는 예외 — 이 계열만 삼킨다.
     * <p>Spring 번역 타입({@code DataAccessResourceFailureException} = 연결 실패,
     * {@code QueryTimeoutException} = 명령 타임아웃)과 번역 전 원인 양쪽을 본다.
     * <p>여기에 {@link RedisException} <b>하위 타입 전체</b>를 넣지 않는다. lettuce-core 6.6.0 의
     * 계층을 전수 확인한 결과 {@code RedisProtocolException}, {@code cluster.PartitionException},
     * {@code dynamic.CommandCreationException}, {@code CacheFrontend.ValueRetrievalException} 이
     * 가용성과 무관한 형제로 존재한다 — 이들까지 삼키면 프로토콜·명령 구성 결함이 캐시 미스로 위장된다.
     * 원인 없는 연결 종료는 {@link #isBareAvailabilityRedisException(Throwable)} 이 <b>정확히
     * {@code RedisException} 타입인 경우만</b> 별도로 인정한다.
     */
    private static final List<Class<? extends Throwable>> AVAILABILITY_FAULTS = List.of(
            DataAccessResourceFailureException.class,
            QueryTimeoutException.class,
            RedisConnectionException.class,
            RedisCommandTimeoutException.class,
            IOException.class);

    /**
     * 원인이 <b>없는</b> bare {@code RedisException} 을 가용성 장애로 볼지.
     *
     * <p>연결이 이미 끊긴 뒤 들어온 명령은 I/O 를 타지 않으므로 원인 없는 {@code RedisException}
     * 으로 온다 — lettuce-core 6.6.0 {@code DefaultEndpoint} 가 {@code "Connection is closed"},
     * {@code "Currently not connected. Commands are rejected."}, {@code "Connection disconnected"}
     * 를 {@code new RedisException(String)} 으로 만든다(바이트코드 확인). Spring 의
     * {@code LettuceExceptionConverter} 는 {@code RedisSystemException} 으로 감쌀 뿐
     * {@link IOException} 을 원인에 붙이지 않는다. 이 형태를 되던지면 "Redis 가 죽어도 조회가
     * 5xx 로 실패하지 않는다"는 계약이 <b>in-flight 연결 종료</b>에서 깨진다.
     *
     * <p>{@code getClass() == RedisException.class} 로 <b>정확 일치</b>만 본다 — 하위 타입은
     * 위 문단의 이유로 전부 제외된다.
     *
     * <p><b>알려진 한계</b>: bare {@code RedisException} 을 만드는 비가용성 경로도 존재한다
     * (pub/sub {@code "not allowed while subscribed"}, {@code "Unsupported script output type"},
     * {@code "Attempting to write duplicate command"}). 셋 다 Spring Cache 의 get/put/evict 경로에는
     * 나타나지 않는다 — 이 캐시는 pub/sub 도 Lua 스크립트도 쓰지 않는다. 메시지 매칭으로 더 좁히는
     * 대신 타입 정확 일치에서 멈춘 이유는, 메시지는 Lettuce 버전에 따라 바뀌고 그 경우
     * <b>조용한 과삼킴이 아니라 in-flight 종료의 5xx 회귀</b>로 나타나 더 위험하기 때문이다.
     */
    private static boolean isBareAvailabilityRedisException(Throwable cause) {
        return cause.getClass() == RedisException.class;
    }

    @Override
    public void handleCacheGetError(RuntimeException exception, Cache cache, Object key) {
        rethrowIfNotAvailabilityFault(exception);
        // 삼킨다 — 미스로 취급돼 타깃 메서드(DB)가 실행된다. 흔적은 메트릭에 남는다.
        count(cache, "get");
        log.debug("캐시 조회 실패 — DB 로 우회. cache={}, key={}", cacheName(cache), key, exception);
    }

    @Override
    public void handleCachePutError(RuntimeException exception, Cache cache, Object key, Object value) {
        rethrowIfNotAvailabilityFault(exception);
        count(cache, "put");
        log.debug("캐시 적재 실패 — 후속 요청이 DB 를 친다. cache={}, key={}", cacheName(cache), key, exception);
    }

    @Override
    public void handleCacheEvictError(RuntimeException exception, Cache cache, Object key) {
        rethrowIfNotAvailabilityFault(exception);
        count(cache, "evict");
        // stale 창이 열렸다. TTL 만료 전까지 낡은 값이 서빙된다 — WARN 으로 올린다.
        log.warn("캐시 무효화 실패 — TTL 만료까지 stale 값이 서빙될 수 있다. cache={}, key={}",
                cacheName(cache), key, exception);
    }

    @Override
    public void handleCacheClearError(RuntimeException exception, Cache cache) {
        rethrowIfNotAvailabilityFault(exception);
        count(cache, "clear");
        log.warn("캐시 전체 무효화 실패 — TTL 만료까지 stale 값이 서빙될 수 있다. cache={}",
                cacheName(cache), exception);
    }

    /**
     * 가용성 장애가 아니면 그대로 되던진다 — 캐시 <b>고장</b>을 캐시 <b>부재</b>로 위장하지 않는다.
     * 원인 체인 전체를 훑는 이유는 Spring 이 Lettuce 예외를 여러 겹으로 감싸기 때문이다.
     */
    private static void rethrowIfNotAvailabilityFault(RuntimeException exception) {
        // A→B→A 같은 다중 노드 순환도 있으므로 identity 기준 방문 집합으로 끊는다.
        // 자기참조(cause == this)만 막으면 2노드 순환에서 이 스레드가 영원히 돈다.
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean availability = false;

        for (Throwable cause = exception; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (matchesAny(cause, NEVER_SWALLOWED)) {
                throw exception;   // deny 가 allow 를 이긴다 — 계층상 형제라 순서가 결과를 바꾼다
            }
            if (matchesAny(cause, AVAILABILITY_FAULTS) || isBareAvailabilityRedisException(cause)) {
                availability = true;
            }
        }

        if (!availability) {
            throw exception;
        }
    }

    private static boolean matchesAny(Throwable cause, List<Class<? extends Throwable>> types) {
        return types.stream().anyMatch(type -> type.isInstance(cause));
    }

    private void count(Cache cache, String operation) {
        Counter.builder(METRIC_NAME)
                .tag("cache", cacheName(cache))
                .tag("operation", operation)
                .description("Redis 캐시 연산 실패로 fallback 이 발동한 횟수 (L-006)")
                .register(meterRegistryProvider.getObject())
                .increment();
    }

    private static String cacheName(Cache cache) {
        return cache == null ? "unknown" : cache.getName();
    }
}
