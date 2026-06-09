package com.peekcart.global.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.actuate.metrics.cache.CacheMetricsRegistrar;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.time.Duration;

/**
 * Redis 기반 캐시 설정.
 * <p>JSON 직렬화, 캐시별 TTL, 키 프리픽스({@code cache:})를 구성한다.
 * 기존 JWT 블랙리스트 키({@code bl:}, {@code gp:})와 네임스페이스를 분리한다.
 *
 * <p>Phase 3 Task 3-4 부하 테스트 시 캐싱 전/후 TPS 비교를 위해
 * {@code peekcart.cache.enabled} 프로퍼티로 캐시 매니저를 토글한다.
 * 기본값은 {@code true} 이며, {@code false} 일 경우 {@link NoOpCacheManager} 가 주입되어
 * {@code @Cacheable} 이 pass-through 로 동작한다.
 */
@Configuration
@EnableCaching
public class CacheConfig {

    public static final String PRODUCT_DETAIL_CACHE = "product";
    public static final String PRODUCT_LIST_CACHE = "products";

    @Bean
    @ConditionalOnProperty(name = "peekcart.cache.enabled", havingValue = "true", matchIfMissing = true)
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
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
                .prefixCacheNameWith("cache:")
                .entryTtl(Duration.ofMinutes(10))
                .disableCachingNullValues();

        RedisCacheConfiguration productDetailConfig = defaultConfig
                .entryTtl(Duration.ofMinutes(30));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration(PRODUCT_DETAIL_CACHE, productDetailConfig)
                .withCacheConfiguration(PRODUCT_LIST_CACHE, defaultConfig)
                .enableStatistics()
                .build();
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

    @Bean
    @ConditionalOnProperty(name = "peekcart.cache.enabled", havingValue = "true", matchIfMissing = true)
    public ApplicationRunner cacheMetricsBinder(
            CacheManager cacheManager,
            CacheMetricsRegistrar cacheMetricsRegistrar,
            MeterRegistry meterRegistry) {
        return args -> {
            bindConfiguredCache(cacheManager, cacheMetricsRegistrar, meterRegistry, PRODUCT_DETAIL_CACHE);
            bindConfiguredCache(cacheManager, cacheMetricsRegistrar, meterRegistry, PRODUCT_LIST_CACHE);
        };
    }

    private static void bindConfiguredCache(
            CacheManager cacheManager,
            CacheMetricsRegistrar cacheMetricsRegistrar,
            MeterRegistry meterRegistry,
            String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache == null || isCacheAlreadyBound(meterRegistry, cacheName)) {
            return;
        }
        cacheMetricsRegistrar.bindCacheToRegistry(cache, Tag.of("cache.manager", "cache"));
    }

    private static boolean isCacheAlreadyBound(MeterRegistry meterRegistry, String cacheName) {
        return meterRegistry.find("cache.gets")
                .tag("cache.manager", "cache")
                .tag("name", cacheName)
                .meter() != null;
    }
}
