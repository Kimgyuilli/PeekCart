package com.peekcart.global.config;

import io.lettuce.core.RedisBusyException;
import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.RedisCommandInterruptedException;
import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import io.lettuce.core.RedisException;
import io.lettuce.core.RedisLoadingException;
import io.lettuce.core.cluster.PartitionSelectorException;
import io.lettuce.core.cluster.models.partitions.Partitions;
import io.lettuce.core.protocol.RedisProtocolException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.RedisSystemException;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * fail-open 의 <b>경계</b>를 고정한다.
 *
 * <p>가용성 장애(연결 실패·명령 타임아웃)는 삼켜 DB 로 흘리지만, 직렬화 불일치나 잘못된 명령 같은
 * 정합성 오류는 되던져야 한다. 둘을 같이 삼키면 "Redis 가 죽었다"와 "캐시가 고장 났다"가 하나의
 * 신호로 뭉개져, 배포 후 모든 요청이 조용히 DB 로 가는 상태의 원인을 추적할 수 없다.
 */
@DisplayName("ResilientCacheErrorHandler — fail-open 경계")
class ResilientCacheErrorHandlerTest {

    private static final Cache CACHE = new ConcurrentMapCache("product");

    private MeterRegistry registry;
    private ResilientCacheErrorHandler handler;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        ObjectProvider<MeterRegistry> provider = new StubProvider(registry);
        handler = new ResilientCacheErrorHandler(provider);
    }

    @Nested
    @DisplayName("가용성 장애는 삼키고 메트릭을 남긴다")
    class AvailabilityFaults {

        @Test
        @DisplayName("연결 실패 · 명령 타임아웃 · 소켓 리셋 · 여러 겹 래핑 모두 삼킨다")
        void swallowsConnectivityFailures() {
            Stream.of(
                    new RedisConnectionFailureException("연결 거부"),
                    new QueryTimeoutException("명령 타임아웃"),
                    new RedisSystemException("래핑됨", new RedisCommandTimeoutException("무응답")),
                    new RedisSystemException("2겹 래핑",
                            new IllegalStateException("중간", new RedisConnectionException("끊김"))),
                    // 실측된 연결 거부 형태: RedisSystemException ← RedisException ← SocketException
                    new RedisSystemException("실측 형태",
                            new RedisException(new java.net.SocketException("Connection reset")))
            ).forEach(exception ->
                    assertThatCode(() -> handler.handleCacheGetError(exception, CACHE, 1L))
                            .as("가용성 장애(%s)는 삼켜야 한다", exception.getClass().getSimpleName())
                            .doesNotThrowAnyException());

            assertThat(fallbackCount("get")).isEqualTo(5.0);
        }

        /**
         * 연결이 이미 끊긴 뒤 들어온 명령은 I/O 를 타지 않아 <b>원인이 없는</b> bare
         * {@code RedisException} 으로 온다. lettuce-core 6.6.0 {@code DefaultEndpoint} 가
         * 실제로 만드는 메시지들이다 — 이걸 되던지면 in-flight 연결 종료에서 조회가 5xx 가 된다.
         */
        @Test
        @DisplayName("원인 없는 bare RedisException(연결 종료 상태)도 삼킨다")
        void swallowsBareConnectionStateException() {
            Stream.of("Connection is closed",
                            "Currently not connected. Commands are rejected.",
                            "Connection disconnected")
                    .map(message -> new RedisSystemException("wrapped", new RedisException(message)))
                    .forEach(exception ->
                            assertThatCode(() -> handler.handleCacheGetError(exception, CACHE, 1L))
                                    .doesNotThrowAnyException());

            assertThat(fallbackCount("get")).isEqualTo(3.0);
        }

        @Test
        @DisplayName("콜백별로 operation 태그가 나뉜다")
        void tagsEachOperationSeparately() {
            RuntimeException fault = new RedisConnectionFailureException("연결 거부");

            handler.handleCacheGetError(fault, CACHE, 1L);
            handler.handleCachePutError(fault, CACHE, 1L, "value");
            handler.handleCacheEvictError(fault, CACHE, 1L);
            handler.handleCacheClearError(fault, CACHE);

            assertThat(fallbackCount("get")).isEqualTo(1.0);
            assertThat(fallbackCount("put")).isEqualTo(1.0);
            assertThat(fallbackCount("evict")).isEqualTo(1.0);
            assertThat(fallbackCount("clear")).isEqualTo(1.0);
        }
    }

    @Nested
    @DisplayName("정합성 오류는 되던지고 메트릭을 남기지 않는다")
    class CorrectnessFaults {

        @Test
        @DisplayName("직렬화 실패는 삼키지 않는다 — 캐시 고장을 캐시 부재로 위장하면 안 된다")
        void rethrowsSerializationFailure() {
            RuntimeException serialization =
                    new org.springframework.data.redis.serializer.SerializationException("타입 불일치");

            assertThatThrownBy(() -> handler.handleCachePutError(serialization, CACHE, 1L, "value"))
                    .isSameAs(serialization);

            assertThat(fallbackCount("put"))
                    .as("되던진 예외를 fallback 으로 집계하면 장애 원인 판별이 오염된다")
                    .isZero();
        }

        @Test
        @DisplayName("서버가 명령을 거부한 경우는 되던진다 — 연결은 멀쩡하다 (ACL/문법/OOM)")
        void rethrowsCommandExecutionFailure() {
            RuntimeException nopermWrapped = new RedisSystemException("래핑됨",
                    new RedisCommandExecutionException("NOPERM this user has no permissions"));

            assertThatThrownBy(() -> handler.handleCacheGetError(nopermWrapped, CACHE, 1L))
                    .as("RedisCommandExecutionException 은 RedisException 하위라 허용 목록에 잡아먹히기 쉽다")
                    .isSameAs(nopermWrapped);

            assertThat(fallbackCount("get")).isZero();
        }

        @Test
        @DisplayName("알 수 없는 런타임 예외도 되던진다 (기본 SimpleCacheErrorHandler 의미 보존)")
        void rethrowsUnknownRuntimeException() {
            RuntimeException unknown = new IllegalStateException("캐시 구현 결함");

            assertThatThrownBy(() -> handler.handleCacheGetError(unknown, CACHE, 1L))
                    .isSameAs(unknown);
            assertThatThrownBy(() -> handler.handleCacheEvictError(unknown, CACHE, 1L))
                    .isSameAs(unknown);

            assertThat(fallbackCount("get")).isZero();
            assertThat(fallbackCount("evict")).isZero();
        }

        @Test
        @DisplayName("LOADING/BUSY 등 서버 오류 하위 계열도 되던진다 — deny 가 allow 보다 먼저다")
        void rethrowsCommandExecutionSubclasses() {
            // 이들은 전부 RedisCommandExecutionException 하위다. allow 에 RedisException 이
            // 있으므로, deny 를 먼저 보지 않으면 조용히 삼켜진다.
            Stream.<RuntimeException>of(
                    new RedisSystemException("loading", new RedisLoadingException("LOADING")),
                    new RedisSystemException("busy", new RedisBusyException("BUSY"))
            ).forEach(exception ->
                    assertThatThrownBy(() -> handler.handleCacheGetError(exception, CACHE, 1L))
                            .isSameAs(exception));

            assertThat(fallbackCount("get")).isZero();
        }

        /**
         * lettuce-core 6.6.0 계층 전수 확인 결과 {@code RedisException} 아래에는 가용성과 무관한
         * 형제가 산다. 최상위 타입을 통째로 허용하면 이들이 캐시 미스로 위장된다.
         */
        @Test
        @DisplayName("RedisException 의 비가용성 하위 계열(protocol/cluster/dynamic)은 되던진다")
        void rethrowsNonAvailabilityRedisExceptionSubclasses() {
            Stream.<RuntimeException>of(
                    new RedisSystemException("protocol", new RedisProtocolException("알 수 없는 응답 타입")),
                    new RedisSystemException("cluster",
                            new PartitionSelectorException("슬롯 미할당", new Partitions()))
            ).forEach(exception ->
                    assertThatThrownBy(() -> handler.handleCacheGetError(exception, CACHE, 1L))
                            .as("%s 는 가용성 장애가 아니다", exception.getCause().getClass().getSimpleName())
                            .isSameAs(exception));

            assertThat(fallbackCount("get")).isZero();
        }

        @Test
        @DisplayName("스레드 interrupt 로 중단된 명령은 되던진다 — Redis 상태와 무관하다")
        void rethrowsCommandInterrupted() {
            RuntimeException interrupted = new RedisSystemException("중단됨",
                    new RedisCommandInterruptedException(new InterruptedException("취소")));

            assertThatThrownBy(() -> handler.handleCacheGetError(interrupted, CACHE, 1L))
                    .as("삼키면 이미 취소된 요청이 DB 조회를 계속한다")
                    .isSameAs(interrupted);

            assertThat(fallbackCount("get")).isZero();
        }

        @Test
        @DisplayName("자기참조 원인 체인에서 무한 루프하지 않는다")
        void terminatesOnSelfReferencingCause() {
            RuntimeException selfReferencing = new IllegalStateException("순환") {
                @Override
                public synchronized Throwable getCause() {
                    return this;
                }
            };

            assertThatThrownBy(() -> handler.handleCacheGetError(selfReferencing, CACHE, 1L))
                    .isSameAs(selfReferencing);
        }

        @Test
        @DisplayName("A→B→A 다중 노드 순환에서도 종료한다 (자기참조 방어만으로는 못 막는다)")
        void terminatesOnMultiNodeCycle() {
            Throwable[] holder = new Throwable[1];
            RuntimeException a = new IllegalStateException("A") {
                @Override
                public synchronized Throwable getCause() {
                    return holder[0];
                }
            };
            holder[0] = new IllegalStateException("B") {
                @Override
                public synchronized Throwable getCause() {
                    return a;
                }
            };

            assertThatThrownBy(() -> handler.handleCacheGetError(a, CACHE, 1L))
                    .isSameAs(a);
        }
    }

    private double fallbackCount(String operation) {
        var counter = registry.find("cache.fallback")
                .tag("cache", "product")
                .tag("operation", operation)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** {@link ObjectProvider} 의 최소 스텁 — 지연 해석 자체는 통합 테스트가 검증한다. */
    private record StubProvider(MeterRegistry registry) implements ObjectProvider<MeterRegistry> {
        @Override
        public MeterRegistry getObject() {
            return registry;
        }

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
    }
}
