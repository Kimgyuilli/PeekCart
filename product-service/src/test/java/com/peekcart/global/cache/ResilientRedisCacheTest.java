package com.peekcart.global.cache;

import com.peekcart.global.config.ResilientCacheErrorHandler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.redis.RedisConnectionFailureException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ResilientRedisCache — 로딩 묶기 · 재고 만료 전 갱신 · 장애 시 Pod 사본 (ADR-0046)")
class ResilientRedisCacheTest {

    private static final String KEY = "1";
    private static final Duration HARD = Duration.ofSeconds(5);
    private static final Duration SOFT = Duration.ofSeconds(3);

    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final FlakyCache redis = new FlakyCache("productStock");
    private final AtomicInteger loads = new AtomicInteger();
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    @DisplayName("D2 — 같은 키 동시 miss N 개에 로더는 1회 돈다")
    void concurrentMissesShareOneLoad() throws Exception {
        ResilientRedisCache cache = cache(null, Duration.ofSeconds(5));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Callable<Integer> blocking = () -> {
            loads.incrementAndGet();
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return 42;
        };

        List<Future<Integer>> results = new ArrayList<>();
        results.add(executor.submit(() -> cache.get(KEY, blocking)));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 4; i++) results.add(executor.submit(() -> cache.get(KEY, blocking)));
        awaitCount(ResilientRedisCache.COALESCED_METRIC, 4);
        release.countDown();

        for (Future<Integer> result : results) assertThat(result.get(5, TimeUnit.SECONDS)).isEqualTo(42);
        assertThat(loads).hasValue(1);
        assertThat(redis.get(KEY)).isNotNull();
    }

    @Test
    @DisplayName("D2 — 로더 예외는 기다리던 요청에도 같은 원인으로 전달된다")
    void loaderFailureReachesWaiters() throws Exception {
        ResilientRedisCache cache = cache(null, Duration.ofSeconds(5));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IllegalStateException failure = new IllegalStateException("db down");
        Callable<Integer> failing = () -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            throw failure;
        };

        Future<Integer> leader = executor.submit(() -> cache.get(KEY, failing));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        Future<Integer> waiter = executor.submit(() -> cache.get(KEY, failing));
        awaitCount(ResilientRedisCache.COALESCED_METRIC, 1);
        release.countDown();

        for (Future<Integer> result : List.of(leader, waiter)) {
            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(Cache.ValueRetrievalException.class)
                    .rootCause().isSameAs(failure);
        }
    }

    @Test
    @DisplayName("D2 — 기다림 상한을 넘으면 거절 예외다 (로딩이 멈춰도 요청 스레드를 다 쓰지 않는다)")
    void waiterGivesUpAfterWaitLimit() throws Exception {
        ResilientRedisCache cache = cache(null, Duration.ofMillis(100));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<Integer> leader = executor.submit(() -> cache.get(KEY, () -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return 1;
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> cache.get(KEY, () -> 2)).isInstanceOf(Rejected.class);
        release.countDown();
        assertThat(leader.get(5, TimeUnit.SECONDS)).isEqualTo(1);
    }

    @Test
    @DisplayName("D3 — soft 전에는 Redis 값을 그대로 내고 로더를 부르지 않는다")
    void freshValueIsNotRefreshed() {
        ResilientRedisCache cache = cache(SOFT, Duration.ofSeconds(5));
        assertThat(cache.get(KEY, counting(10))).isEqualTo(10);
        clock.advance(Duration.ofMillis(2_999));

        assertThat(cache.get(KEY, counting(11))).isEqualTo(10);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("D3 — soft 뒤에는 한 요청만 갱신하고 나머지는 기다리지 않고 기존 값을 받는다")
    void staleValueIsRefreshedByOneRequest() throws Exception {
        ResilientRedisCache cache = cache(SOFT, Duration.ofSeconds(5));
        cache.get(KEY, counting(10));
        clock.advance(SOFT);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<Integer> refresher = executor.submit(() -> cache.get(KEY, () -> {
            loads.incrementAndGet();
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return 20;
        }));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        for (int i = 0; i < 4; i++) {
            assertThat(cache.get(KEY, counting(99))).as("갱신 중에는 기존 값을 바로 받는다").isEqualTo(10);
        }
        release.countDown();

        assertThat(refresher.get(5, TimeUnit.SECONDS)).isEqualTo(20);
        assertThat(loads).hasValue(2);
        assertThat(cache.get(KEY, counting(99))).isEqualTo(20);
    }

    @Test
    @DisplayName("D3 — 갱신이 상한에 거절되면 hard 안에서는 기존 값을 낸다")
    void rejectedRefreshServesPreviousValue() {
        ResilientRedisCache cache = cache(SOFT, Duration.ofSeconds(5));
        cache.get(KEY, counting(10));
        clock.advance(Duration.ofSeconds(4));

        Callable<Integer> rejected = () -> { throw new Rejected(); };
        assertThat(cache.get(KEY, rejected)).isEqualTo(10);
        assertThat(count(ResilientRedisCache.REFRESH_REJECTED_METRIC)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("D3 — hard 를 지난 값은 Redis 에 남아 있어도 내지 않고, 거절은 그대로 던진다")
    void valueOlderThanHardIsNeverServed() {
        ResilientRedisCache cache = cache(SOFT, Duration.ofSeconds(5));
        cache.get(KEY, counting(10));
        clock.advance(HARD);

        Callable<Integer> rejected = () -> { throw new Rejected(); };
        assertThatThrownBy(() -> cache.get(KEY, rejected))
                .isInstanceOf(Cache.ValueRetrievalException.class)
                .hasCauseInstanceOf(Rejected.class);
        assertThat(cache.get(KEY, counting(30))).isEqualTo(30);
    }

    @Test
    @DisplayName("D4 — Redis 오류 때 허용 범위 안의 Pod 사본을 내고 DB 를 치지 않는다")
    void redisFailureServesLastValueWithinLimit() {
        ResilientRedisCache cache = cache(null, Duration.ofSeconds(5));
        cache.get(KEY, counting(10));
        redis.down = true;
        clock.advance(Duration.ofMillis(4_999));

        assertThat(cache.get(KEY, counting(11))).isEqualTo(10);
        assertThat(loads).hasValue(1);
        assertThat(count(ResilientRedisCache.STALE_SERVED_METRIC)).isEqualTo(1.0);
        assertThat(fallbacks("get")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("D4 — 허용 범위를 넘은 사본은 내지 않고 DB 를 친다. 그 결과가 다음 장애 응답이 된다")
    void redisFailureBeyondLimitLoads() {
        ResilientRedisCache cache = cache(null, Duration.ofSeconds(5));
        cache.get(KEY, counting(10));
        redis.down = true;
        clock.advance(HARD);

        assertThat(cache.get(KEY, counting(11))).isEqualTo(11);
        assertThat(fallbacks("put")).as("장애 중 적재 실패도 기존처럼 센다").isEqualTo(1.0);
        assertThat(cache.get(KEY, counting(12))).isEqualTo(11);
        assertThat(loads).hasValue(2);
    }

    @Test
    @DisplayName("D5 — Redis 가 정상이면 Pod 사본이 아니라 Redis 값을 낸다")
    void healthyRedisValueWinsOverPodCopy() {
        ResilientRedisCache cache = cache(null, Duration.ofSeconds(5));
        cache.get(KEY, counting(10));
        redis.put(KEY, new CacheEnvelope(20, clock.millis())); // 다른 Pod 가 다시 적재했다

        assertThat(cache.get(KEY, counting(99))).isEqualTo(20);
        assertThat(loads).hasValue(1);
    }

    @Test
    @DisplayName("evict 는 Pod 사본도 지운다 — 이 Pod 는 지운 값을 장애 응답으로 내지 않는다")
    void evictDropsPodCopy() {
        ResilientRedisCache cache = cache(null, Duration.ofSeconds(5));
        cache.get(KEY, counting(10));
        cache.evict(KEY);
        redis.down = true;

        assertThat(cache.get(KEY, counting(11))).isEqualTo(11);
        assertThat(count(ResilientRedisCache.STALE_SERVED_METRIC)).isZero();
    }

    @Test
    @DisplayName("가용성 오류가 아닌 Redis 오류는 삼키지 않는다 (L-006 분류 유지)")
    void nonAvailabilityFailureIsRethrown() {
        ResilientRedisCache cache = cache(null, Duration.ofSeconds(5));
        cache.get(KEY, counting(10));
        redis.broken = new InvalidDataAccessApiUsageException("bad command");

        assertThatThrownBy(() -> cache.get(KEY, counting(11))).isSameAs(redis.broken);
        assertThat(count(ResilientRedisCache.STALE_SERVED_METRIC)).isZero();
    }

    @Test
    @DisplayName("Redis 에는 봉투로 들어가고, 직접 조회(get(key))는 값만 돌려준다")
    void storesEnvelopeAndUnwrapsOnDirectGet() {
        ResilientRedisCache cache = cache(null, Duration.ofSeconds(5));
        cache.get(KEY, counting(10));

        assertThat(redis.get(KEY).get()).isEqualTo(new CacheEnvelope(10, clock.millis()));
        assertThat(cache.get(KEY).get()).isEqualTo(10);
        assertThat(cache.get(KEY, Integer.class)).isEqualTo(10);
    }

    // ---------- helpers ----------

    private ResilientRedisCache cache(Duration softRefresh, Duration waitLimit) {
        ObjectProvider<MeterRegistry> provider = new StubProvider(registry);
        return new ResilientRedisCache(redis, new ResilientRedisCache.Policy(HARD, softRefresh, 100, waitLimit),
                new ResilientCacheErrorHandler(provider), Rejected.class::isInstance, Rejected::new,
                clock, provider);
    }

    private Callable<Integer> counting(int value) {
        return () -> {
            loads.incrementAndGet();
            return value;
        };
    }

    private double count(String metric) {
        var counter = registry.find(metric).tag("cache", redis.getName()).counter();
        return counter == null ? 0 : counter.count();
    }

    private double fallbacks(String operation) {
        var counter = registry.find("cache.fallback").tag("operation", operation).counter();
        return counter == null ? 0 : counter.count();
    }

    private void awaitCount(String metric, double expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (count(metric) < expected) {
            assertThat(System.nanoTime()).as("%s 가 %s 에 닿지 않았다", metric, expected).isLessThan(deadline);
            Thread.sleep(5);
        }
    }

    /** 상한 거절을 흉내 낸다. */
    private static final class Rejected extends RuntimeException {
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-11-13T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    /** 장애를 켜고 끌 수 있는 Redis 대역. 가용성 오류는 Spring 이 번역한 연결 실패 예외로 낸다. */
    private static final class FlakyCache extends ConcurrentMapCache {
        volatile boolean down;
        volatile RuntimeException broken;

        FlakyCache(String name) {
            super(name, false);
        }

        @Override
        public ValueWrapper get(Object key) {
            fail();
            return super.get(key);
        }

        @Override
        public void put(Object key, Object value) {
            fail();
            super.put(key, value);
        }

        private void fail() {
            if (broken != null) throw broken;
            if (down) throw new RedisConnectionFailureException("redis down");
        }
    }

    private record StubProvider(MeterRegistry registry) implements ObjectProvider<MeterRegistry> {
        @Override
        public MeterRegistry getObject(Object... args) {
            return registry;
        }

        @Override
        public MeterRegistry getIfAvailable() {
            return registry;
        }

        @Override
        public MeterRegistry getIfUnique() {
            return registry;
        }

        @Override
        public MeterRegistry getObject() {
            return registry;
        }
    }
}
