package com.peekcart.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.peekcart.global.cache.ResilientRedisCache;
import com.peekcart.global.exception.BusinessException;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.domain.exception.ProductException;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.actuate.metrics.cache.CacheMeterBinderProvider;
import org.springframework.boot.actuate.metrics.cache.RedisCacheMetrics;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.CacheStatisticsCollector;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Redis 기반 캐시 설정.
 * <p>JSON 직렬화, 캐시별 TTL, 키 프리픽스({@code cache:})를 구성한다.
 * 기존 JWT 블랙리스트 키({@code bl:}, {@code gp:})와 네임스페이스를 분리한다.
 *
 * <p>Phase 3 Task 3-4 부하 테스트 시 캐싱 전/후 TPS 비교를 위해
 * {@code peekcart.cache.enabled} 프로퍼티로 캐시 매니저를 토글한다.
 * 기본값은 {@code true} 이며, {@code false} 일 경우 {@link NoOpCacheManager} 가 주입되어
 * {@code @Cacheable} 이 pass-through 로 동작한다.
 *
 * <p><b>{@link CachingConfigurer} 를 구현하는 이유</b> (L-006): Spring 은
 * {@code CacheErrorHandler} 를 <b>{@code CachingConfigurer} 빈에서만</b> 수집한다
 * ({@code AbstractCachingConfiguration#setConfigurers}). 맨 {@code @Bean CacheErrorHandler} 는
 * 조용히 무시되므로 반드시 여기서 {@link #errorHandler()} 로 공급해야 한다.
 *
 * <p><b>캐시를 {@link ResilientRedisCache} 로 감싼다</b> (ADR-0046). 같은 키 miss 묶기, 재고 만료 전 갱신,
 * Redis 장애 때 Pod 사본 응답이 여기서 정한 {@link ResilientRedisCache.Policy} 로 동작한다. 감싸면 Boot 의
 * 캐시 메트릭 바인딩(S7)이 끊기므로 {@link #resilientRedisCacheMeterBinderProvider()} 로 다시 잇는다.
 *
 * <p>{@code cacheManager()}/{@code cacheResolver()}/{@code keyGenerator()} 는
 * <b>오버라이드하지 않는다</b>. 인터페이스 기본 구현이 null 을 반환하면
 * {@code CacheAspectSupport#afterSingletonsInstantiated} 가 {@code CacheManager} 타입 조회로
 * 폴백하므로, 아래 {@code @ConditionalOnProperty} 2빈 구조를 그대로 쓸 수 있다.
 * 명시 배선을 넣으면 두 빈 사이에서 모호성만 만든다.
 */
@Configuration
@EnableCaching(order = -200)
public class CacheConfig implements CachingConfigurer {

    public static final String PRODUCT_DETAIL_CACHE = "product";
    public static final String PRODUCT_LIST_CACHE = "products";

    /**
     * 재고 전용 캐시 (ADR-0026 D2). 상품 정보 캐시와 <b>분리</b>하는 이유는 변경 빈도가
     * 두 자릿수 이상 다르기 때문이다 — 한 엔트리에 합치면 둘 중 하나는 반드시 틀린 TTL 을 갖는다.
     */
    public static final String PRODUCT_STOCK_CACHE = "productStock";

    /**
     * 재고 캐시 TTL 기본값 = <b>노출 가능한 stale 재고의 상한</b> (ADR-0026 D2).
     * 쓰기 경로에 무효화를 배선하지 않으므로(D3) 이 값이 곧 최악값이다.
     *
     * <p>5초인 근거: 포화 기준 상세 ≈ 463 rps 에서 상품당 재고 조회가 <b>5초에 1회</b>로 떨어진다.
     * 더 늘리면 DB 조회 감소폭은 수확 체감하는데 stale 상한은 선형으로 늘고, 1초 미만으로 줄이면
     * 적중률이 떨어져 목적이 사라진다. 상세의 {@code stock} 은 예약 보증이 아니라
     * <b>표시용 힌트</b>라는 계약(ADR-0026 D1)이 이 완화를 허용한다.
     *
     * <p><b>기본값은 여기(Java Config)가 소유한다</b> — 동작 규약이므로 프로파일에 두지 않는다
     * (ADR-0007). 프로퍼티를 뚫어둔 것은 <b>stale 상한을 결정적으로 시험하기 위해서</b>이고
     * ({@code ProductStockCacheStalenessIntegrationTest}), 환경별로 다르게 쓰라는 뜻이 아니다.
     */
    public static final String PRODUCT_STOCK_TTL_PROPERTY = "peekcart.cache.product-stock-ttl";

    /**
     * Redis 키 접두사. 값을 {@code CacheEnvelope} 로 싸면서 {@code cache:} 에서 바꿨다 (D-109).
     * 같은 키에 새 형식을 쓰면 롤링 배포 중 옛 Pod 가 모르는 타입을 읽어 {@code SerializationException}(500)을 낸다.
     */
    public static final String KEY_PREFIX = "cache:v2:";

    private static final Duration PRODUCT_DETAIL_TTL = Duration.ofMinutes(30);
    private static final Duration PRODUCT_LIST_TTL = Duration.ofMinutes(10);

    /** 재고 soft 갱신 시점 (ADR-0046 D3). hard(재고 TTL) 이상이면 갱신하지 않고 hard 만료만 남는다. */
    static final Duration PRODUCT_STOCK_SOFT_REFRESH = Duration.ofSeconds(3);

    /** 다른 요청의 로딩을 기다리는 상한. 넘으면 {@code PRD-005}(503). */
    static final Duration LOAD_WAIT_LIMIT = Duration.ofSeconds(3);

    /**
     * {@link MeterRegistry} 를 <b>직접 주입하지 않는다</b>. {@code @Configuration} 클래스가 레지스트리를
     * 생성자에서 요구하면 {@code MeterRegistryCustomizer}(공통 태그 {@code application=product-service} 등)
     * 가 적용되기 <b>전에</b> 레지스트리가 만들어져, 이후 모든 메트릭에서 그 태그가 사라진다
     * (ADR-0009 S2 위반 — {@code ProductObservabilityMetricsIntegrationTest} 가 이를 잡는다).
     * {@link ObjectProvider} 로 미뤄 첫 사용 시점에 완성된 레지스트리를 받는다.
     */
    private final ObjectProvider<MeterRegistry> meterRegistryProvider;
    private final ResilientCacheErrorHandler errorHandler;

    public CacheConfig(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistryProvider = meterRegistryProvider;
        this.errorHandler = new ResilientCacheErrorHandler(meterRegistryProvider);
    }

    @Bean
    @ConditionalOnProperty(name = "peekcart.cache.enabled", havingValue = "true", matchIfMissing = true)
    public RedisCacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            @Value("${" + PRODUCT_STOCK_TTL_PROPERTY + ":5s}") Duration productStockTtl,
            ObjectProvider<Clock> clockProvider) {
        ObjectMapper objectMapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .activateDefaultTyping(
                        BasicPolymorphicTypeValidator.builder()
                                .allowIfBaseType("com.peekcart.")
                                .allowIfBaseType("java.lang.")
                                .allowIfBaseType("java.util.")
                                .build(),
                        ObjectMapper.DefaultTyping.EVERYTHING,
                        JsonTypeInfo.As.PROPERTY)
                .build();

        GenericJackson2JsonRedisSerializer serializer =
                new GenericJackson2JsonRedisSerializer(objectMapper);

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(RedisSerializationContext.SerializationPair
                        .fromSerializer(serializer))
                .prefixCacheNameWith(KEY_PREFIX)
                .entryTtl(PRODUCT_LIST_TTL)
                .disableCachingNullValues();

        RedisCacheConfiguration productDetailConfig = defaultConfig
                .entryTtl(PRODUCT_DETAIL_TTL);

        RedisCacheConfiguration productStockConfig = defaultConfig
                .entryTtl(productStockTtl);

        // Pod 사본의 허용 범위는 Redis TTL 과 같다(ADR-0046 D4). 재고는 장애 중에도 ADR-0026 D2 의 상한을 지킨다.
        Map<String, ResilientRedisCache.Policy> policies = Map.of(
                PRODUCT_DETAIL_CACHE, new ResilientRedisCache.Policy(
                        PRODUCT_DETAIL_TTL, null, 10_000, LOAD_WAIT_LIMIT),
                PRODUCT_LIST_CACHE, new ResilientRedisCache.Policy(
                        PRODUCT_LIST_TTL, null, 1_000, LOAD_WAIT_LIMIT),
                PRODUCT_STOCK_CACHE, new ResilientRedisCache.Policy(
                        productStockTtl,
                        PRODUCT_STOCK_SOFT_REFRESH.compareTo(productStockTtl) < 0 ? PRODUCT_STOCK_SOFT_REFRESH : null,
                        10_000, LOAD_WAIT_LIMIT));
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);

        // builder(connectionFactory).enableStatistics() 와 같은 writer 다. builder 로는 decorateCache 를 바꿀 수 없다.
        RedisCacheWriter writer = RedisCacheWriter.nonLockingRedisCacheWriter(connectionFactory)
                .withStatisticsCollector(CacheStatisticsCollector.create());
        return new ResilientRedisCacheManager(writer, defaultConfig, Map.of(
                PRODUCT_DETAIL_CACHE, productDetailConfig,
                PRODUCT_LIST_CACHE, defaultConfig,
                PRODUCT_STOCK_CACHE, productStockConfig),
                cache -> {
                    ResilientRedisCache.Policy policy = policies.get(cache.getName());
                    return policy == null ? cache : new ResilientRedisCache(cache, policy, errorHandler,
                            CacheConfig::isReadRejected, () -> new ProductException(ErrorCode.PRD_005),
                            clock, meterRegistryProvider);
                });
    }

    /** 로더 예외가 DB 진입 상한의 거절({@code PRD-005}, ADR-0037)인지. 캐시 advice 가 원인을 한 겹 감싸므로 체인을 본다. */
    private static boolean isReadRejected(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof BusinessException business && business.getErrorCode() == ErrorCode.PRD_005) {
                return true;
            }
        }
        return false;
    }

    /**
     * {@link ResilientRedisCache} 에 S7 캐시 메트릭({@code cache_gets_total} 등)을 묶는다.
     * <p>Boot 의 {@code CacheMetricsRegistrar} 는 {@code TransactionAwareCacheDecorator} 만 벗기므로, 감싼 캐시는
     * Redis 바인더가 알아보지 못해 메트릭이 사라진다. 안쪽 {@link RedisCache} 로 같은 {@link RedisCacheMetrics} 를 만든다.
     */
    @Bean
    CacheMeterBinderProvider<ResilientRedisCache> resilientRedisCacheMeterBinderProvider() {
        return new CacheMeterBinderProvider<>() {
            @Override
            public MeterBinder getMeterBinder(ResilientRedisCache cache, Iterable<Tag> tags) {
                return new RedisCacheMetrics((RedisCache) cache.getTarget(), tags);
            }
        };
    }

    /** {@code decorateCache} 에서 Redis 캐시를 감싸는 {@link RedisCacheManager}. 빈 타입과 이름은 그대로다. */
    static final class ResilientRedisCacheManager extends RedisCacheManager {

        private final UnaryOperator<Cache> decorator;

        ResilientRedisCacheManager(RedisCacheWriter writer, RedisCacheConfiguration defaults,
                                   Map<String, RedisCacheConfiguration> initialCaches,
                                   UnaryOperator<Cache> decorator) {
            super(writer, defaults, true, initialCaches);
            this.decorator = decorator;
        }

        @Override
        protected Cache decorateCache(Cache cache) {
            return decorator.apply(super.decorateCache(cache));
        }
    }

    /**
     * 캐시 비활성화 시 주입되는 NoOp 캐시 매니저.
     * <p>부하 테스트 baseline 측정 (캐시 OFF) 전용.
     */
    @Bean
    @ConditionalOnProperty(name = "peekcart.cache.enabled", havingValue = "false")
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }

    /**
     * Redis 장애 시 조회를 DB 로 흘리는 fail-open 핸들러 (L-006).
     * <p>정책 근거와 콜백별 트레이드오프는 {@link ResilientCacheErrorHandler} javadoc 참고.
     */
    @Override
    public CacheErrorHandler errorHandler() {
        return errorHandler;
    }
}
