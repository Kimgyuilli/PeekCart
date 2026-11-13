package com.peekcart.global.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.SimpleValueWrapper;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Redis 캐시를 감싸 miss 를 묶고, 재고를 만료 전에 갱신하고, Redis 장애 때 Pod 사본으로 응답한다 (ADR-0046 D2~D5).
 *
 * <p><b>{@link #get(Object, Callable)} 가 본 경로다.</b> {@code @Cacheable(sync = true)} 는 이 메서드만 부른다.
 * Redis 가용성 오류를 밖으로 던지면 Spring 은 errorHandler 를 거쳐 메서드를 그대로 다시 부르므로 묶기가 사라진다.
 * 그래서 가용성 오류는 여기서 {@link CacheErrorHandler} 로 분류·계수하고 직접 처리한다. 그 밖의 오류는 핸들러가 되던진다.
 *
 * <p><b>Pod 사본은 평상시 응답에 쓰지 않는다</b>(D5). Redis 가 정상이면 정보·목록은 Redis 값을 그대로 낸다.
 * 사본은 Redis 오류 때만 낸다. 장애 중 다른 Pod 에서 수정해도 이 Pod 의 사본은 허용 범위까지 남는다(ADR-0046 Consequences).
 *
 * <p>로더는 캐시 advice 안쪽의 DB 진입 상한(ADR-0037)을 지난다. 다른 요청의 로딩을 기다리는 요청은 permit 도
 * 커넥션도 잡지 않는다. 기다림에는 상한({@link Policy#waitLimit()})을 둬서 로딩이 멈춘 동안 같은 키의 요청이
 * 요청 스레드를 다 쓰지 않게 한다.
 */
public class ResilientRedisCache implements Cache {

    /** 다른 요청의 로딩을 기다려 받은 요청 수 (D2). */
    public static final String COALESCED_METRIC = "cache.load.coalesced";
    /** Redis 오류로 Pod 사본을 낸 응답 수 (D4). */
    public static final String STALE_SERVED_METRIC = "cache.stale.served";
    /** soft 갱신이 상한에 거절돼 기존 값을 낸 수 (D3). */
    public static final String REFRESH_REJECTED_METRIC = "cache.refresh.rejected";

    /**
     * 캐시별 정책.
     *
     * @param hardLimit    값의 최대 나이. Redis 장애 때 Pod 사본을 낼 수 있는 범위이기도 하다(D4)
     * @param softRefresh  이 나이를 지난 Redis 값은 한 요청이 갱신한다(D3). null 이면 갱신하지 않는다
     * @param maximumSize  Pod 사본 개수 상한
     * @param waitLimit    다른 요청의 로딩을 기다리는 상한
     */
    public record Policy(Duration hardLimit, Duration softRefresh, long maximumSize, Duration waitLimit) {
    }

    private final Cache redis;
    private final Policy policy;
    private final CacheErrorHandler errorHandler;
    private final Predicate<Throwable> isLoadRejected;
    private final Supplier<RuntimeException> waitTimedOut;
    private final Clock clock;
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final com.github.benmanes.caffeine.cache.Cache<Object, CacheEnvelope> lastValues;
    private final ConcurrentHashMap<Object, CompletableFuture<Object>> loads = new ConcurrentHashMap<>();

    /**
     * @param isLoadRejected 로더 예외가 DB 진입 상한의 거절인지
     * @param waitTimedOut   기다림 상한을 넘었을 때 던질 예외
     */
    public ResilientRedisCache(Cache redis, Policy policy, CacheErrorHandler errorHandler,
                               Predicate<Throwable> isLoadRejected, Supplier<RuntimeException> waitTimedOut,
                               Clock clock, ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.redis = redis;
        this.policy = policy;
        this.errorHandler = errorHandler;
        this.isLoadRejected = isLoadRejected;
        this.waitTimedOut = waitTimedOut;
        this.clock = clock;
        this.meterRegistryProvider = meterRegistryProvider;
        this.lastValues = Caffeine.newBuilder()
                .maximumSize(policy.maximumSize())
                .expireAfterWrite(policy.hardLimit())
                .build();
    }

    /** 감싼 Redis 캐시. 캐시 메트릭(ADR-0009 S7)을 이 캐시에 묶는다. */
    public Cache getTarget() {
        return redis;
    }

    @Override
    public String getName() {
        return redis.getName();
    }

    @Override
    public Object getNativeCache() {
        return redis.getNativeCache();
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        CacheEnvelope cached;
        try {
            cached = unwrap(redis.get(key));
        } catch (RuntimeException ex) {
            errorHandler.handleCacheGetError(ex, this, key); // 가용성 오류가 아니면 되던진다
            CacheEnvelope last = lastValues.getIfPresent(key);
            if (last != null && age(last).compareTo(policy.hardLimit()) < 0) {
                count(STALE_SERVED_METRIC);
                return (T) last.value();
            }
            return (T) load(key, valueLoader);
        }
        if (cached == null || age(cached).compareTo(policy.hardLimit()) >= 0) {
            return (T) load(key, valueLoader);
        }
        lastValues.put(key, cached);
        if (policy.softRefresh() != null && age(cached).compareTo(policy.softRefresh()) >= 0) {
            return (T) refresh(key, valueLoader, cached);
        }
        return (T) cached.value();
    }

    /** miss: 진행 중인 로딩이 있으면 그 결과를 기다리고, 없으면 이 요청이 로딩한다 (D2). */
    private Object load(Object key, Callable<?> valueLoader) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        CompletableFuture<Object> inFlight = loads.putIfAbsent(key, mine);
        if (inFlight != null) {
            count(COALESCED_METRIC);
            return await(key, valueLoader, inFlight);
        }
        try {
            Object value = loadAndStore(key, valueLoader);
            mine.complete(value);
            return value;
        } catch (Throwable ex) {
            mine.completeExceptionally(ex);
            throw new ValueRetrievalException(key, valueLoader, ex);
        } finally {
            loads.remove(key, mine);
        }
    }

    /**
     * soft 를 지난 값: 갱신을 맡은 한 요청만 로딩하고 나머지는 기존 값을 바로 받는다 (D3).
     * 갱신이 상한에 거절되면 기존 값을 낸다. 값의 나이는 hard 안이므로 ADR-0026 D2 를 지킨다.
     */
    private Object refresh(Object key, Callable<?> valueLoader, CacheEnvelope cached) {
        CompletableFuture<Object> mine = new CompletableFuture<>();
        if (loads.putIfAbsent(key, mine) != null) {
            return cached.value();
        }
        try {
            Object value = loadAndStore(key, valueLoader);
            mine.complete(value);
            return value;
        } catch (Throwable ex) {
            mine.completeExceptionally(ex);
            if (isLoadRejected.test(ex)) {
                count(REFRESH_REJECTED_METRIC);
                return cached.value();
            }
            throw new ValueRetrievalException(key, valueLoader, ex);
        } finally {
            loads.remove(key, mine);
        }
    }

    private Object await(Object key, Callable<?> valueLoader, CompletableFuture<Object> inFlight) {
        try {
            return inFlight.get(policy.waitLimit().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            throw waitTimedOut.get();
        } catch (ExecutionException ex) {
            throw new ValueRetrievalException(key, valueLoader, ex.getCause());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
    }

    private Object loadAndStore(Object key, Callable<?> valueLoader) throws Exception {
        long startedAt = clock.millis();
        Object value = valueLoader.call();
        CacheEnvelope envelope = new CacheEnvelope(value, startedAt);
        try {
            redis.put(key, envelope);
        } catch (RuntimeException ex) {
            errorHandler.handleCachePutError(ex, this, key, value); // 가용성 오류가 아니면 되던진다
        }
        lastValues.put(key, envelope);
        return value;
    }

    @Override
    public ValueWrapper get(Object key) {
        CacheEnvelope cached = unwrap(redis.get(key));
        return cached == null ? null : new SimpleValueWrapper(cached.value());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        Object value = wrapper == null ? null : wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException("Cached value is not of required type [" + type.getName() + "]: " + value);
        }
        return (T) value;
    }

    @Override
    public void put(Object key, Object value) {
        CacheEnvelope envelope = new CacheEnvelope(value, clock.millis());
        redis.put(key, envelope);
        lastValues.put(key, envelope);
    }

    /** Pod 사본을 먼저 지운다. Redis evict 가 실패해도 이 Pod 는 지운 값을 장애 응답으로 내지 않는다. */
    @Override
    public void evict(Object key) {
        lastValues.invalidate(key);
        redis.evict(key);
    }

    @Override
    public void clear() {
        lastValues.invalidateAll();
        redis.clear();
    }

    private static CacheEnvelope unwrap(ValueWrapper wrapper) {
        return wrapper == null ? null : (CacheEnvelope) wrapper.get();
    }

    private Duration age(CacheEnvelope envelope) {
        return Duration.ofMillis(Math.max(0, clock.millis() - envelope.loadedAtMillis()));
    }

    private void count(String metric) {
        Counter.builder(metric)
                .tag("cache", getName())
                .register(meterRegistryProvider.getObject())
                .increment();
    }
}
