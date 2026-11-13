package com.peekcart.product.infrastructure;

import com.peekcart.product.application.ProductCommandService;
import com.peekcart.product.application.ProductQueryService;
import com.peekcart.product.application.dto.CreateProductCommand;
import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.application.dto.ProductListDto;
import com.peekcart.product.application.dto.UpdateProductCommand;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.repository.ProductRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import eu.rekawek.toxiproxy.model.toxic.Timeout;
import jakarta.persistence.EntityManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.data.redis.RedisConnectionDetails;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Redis 장애 시 조회 캐시 fail-open 검증 (L-006).
 *
 * <p><b>왜 Toxiproxy 인가</b>: 컨테이너를 정지시키면 <b>연결 거부</b>만 재현되고, 정작 위험한
 * <b>무응답</b>(연결은 살아 있는데 응답이 없는 상태)을 만들 수 없다. 무응답에서는 유계 타임아웃이
 * 없으면 Lettuce 기본 60s 를 기다린 뒤에야 fallback 이 불려 fail-open 이 무의미해진다.
 * 또 컨테이너 정지는 되돌릴 수 없어 테스트 간 순서 의존을 만든다.
 *
 * <p><b>두 장애를 다른 기구로 만든다</b>:
 * <ul>
 *   <li>연결 거부 → {@link Proxy#disable()} (프록시가 리스닝을 멈춘다)
 *   <li>무응답 → downstream {@code timeout(_, 0)} toxic (데이터를 끊되 제거 전까지 연결 유지)
 * </ul>
 * bandwidth toxic 은 정체만 만들고 거부하지 않으므로 쓰지 않는다.
 *
 * <p><b>false-green 방어</b>: 기본 {@code SimpleCacheErrorHandler} 는 예외를 되던지므로,
 * {@code CacheConfig#errorHandler()} 배선이 빠지면 아래 테스트들이 자동으로 실패한다.
 * 그 전에 {@link #proxyIsActuallyOnThePath()} 가 <b>앱 트래픽이 프록시를 실제로 경유하는지</b>를
 * 먼저 고정한다 — 이게 없으면 장애를 주입해도 앱이 Redis 에 직결돼 전부 통과해버린다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("Redis 장애 시 상품 조회 fail-open (L-006)")
class ProductCacheFallbackIntegrationTest extends AbstractIntegrationTest {

    private static final PageRequest DEFAULT_PAGE = PageRequest.of(0, 10);
    private static final String DETAIL_CACHE = "product";
    private static final String LIST_CACHE = "products";
    private static final String STOCK_CACHE = "productStock";
    private static final int REDIS_PORT = 6379;
    private static final int PROXY_LISTEN_PORT = 8666;
    private static final String TIMEOUT_TOXIC = "redis-no-response";

    /** Redis 와 Toxiproxy 가 같은 네트워크에 있어야 프록시가 upstream 을 alias 로 찾는다. */
    private static final Network NETWORK = Network.newNetwork();

    /**
     * Redis 는 공유 컨테이너가 아니라 이 클래스가 따로 띄운다. 앱이 이 Redis 앞의 프록시를 거쳐야
     * 장애 주입이 먹는다 — 연결은 {@link RedisThroughProxy} 가 돌린다. MySQL·Kafka 는 공유한다.
     */
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
            .withExposedPorts(REDIS_PORT)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");

    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withNetwork(NETWORK);

    private static Proxy redisProxy;

    static {
        // @Container 가 아니라 static 블록에서 띄운다 — 연결 빈이 평가되기 전에
        // 프록시가 존재해야 하고, 그 순서를 JUnit 확장 순서에 맡기지 않기 위해서다.
        REDIS.start();
        TOXIPROXY.start();
        try {
            redisProxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy("redis", "0.0.0.0:" + PROXY_LISTEN_PORT, "redis:" + REDIS_PORT);
        } catch (IOException e) {
            throw new IllegalStateException("Toxiproxy 프록시 생성 실패", e);
        }
    }

    /**
     * 앱의 Redis 연결을 프록시로 돌린다.
     *
     * <p><b>프로퍼티로는 안 된다</b>: {@link SharedContainers} 가 공유 Redis 의 {@code RedisConnectionDetails}
     * 빈을 내놓으면 Boot 는 {@code spring.data.redis.*} 로 만드는 기본 빈을 건너뛴다({@code @ConditionalOnMissingBean}).
     * 그래서 {@code @Primary} 빈으로 덮는다. Lettuce 와 Redisson 이 모두 이 빈 하나를 받는다.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class RedisThroughProxy {

        @Bean
        @Primary
        RedisConnectionDetails redisThroughProxy() {
            // 람다를 쓸 수 없다 — RedisConnectionDetails 는 메서드가 전부 default 다.
            return new RedisConnectionDetails() {
                @Override
                public Standalone getStandalone() {
                    return Standalone.of(TOXIPROXY.getHost(), TOXIPROXY.getMappedPort(PROXY_LISTEN_PORT));
                }
            };
        }

        /** 캐시 값의 나이(ADR-0046 soft·hard·허용 범위)를 Redis TTL 과 떼어 앞당기기 위한 시계. */
        @Bean
        ShiftableClock cacheClock() {
            return new ShiftableClock();
        }
    }

    /** 실제 시각에 오프셋을 더한다. Redis TTL 은 실제 시간으로 흐르므로, 값의 나이만 앞당길 수 있다. */
    static final class ShiftableClock extends Clock {
        private volatile Duration offset = Duration.ZERO;

        void advance(Duration duration) {
            offset = offset.plus(duration);
        }

        void reset() {
            offset = Duration.ZERO;
        }

        @Override
        public Instant instant() {
            return Instant.now().plus(offset);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    @Autowired javax.sql.DataSource dataSource;
    @Autowired ProductQueryService queryService;
    @Autowired ProductCommandService commandService;
    @Autowired TestRestTemplate restTemplate;
    @Autowired RedisConnectionFactory redisConnectionFactory;
    @Autowired CacheManager cacheManager;
    @Autowired ShiftableClock clock;

    /** 캐시 미스 시 DB 를 실제로 몇 번 치는지 세기 위한 스파이 (V5). */
    @MockitoSpyBean ProductRepository productRepository;

    private Long categoryId;
    private Long productId;

    @BeforeEach
    void setUp() {
        healRedis();
        cleanDatabase();

        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Category category = Category.create("전자기기", null);
        em.persist(category);
        em.flush();
        categoryId = category.getId();
        em.getTransaction().commit();
        em.close();

        productId = commandService.create(
                new CreateProductCommand(categoryId, "스마트폰", "설명", 1_000_000L, null, 100)).id();

        flushRedis();
        // Pod 사본(ADR-0046 D4)은 Redis 를 비워도 남는다. 앞선 테스트의 사본이 장애 응답으로 새지 않게 함께 지운다.
        cleanCaches(cacheManager);
        clock.reset();
        clearInvocations(productRepository);
    }

    @AfterEach
    void tearDown() {
        healRedis();
    }

    // ---------- V0: 하네스 자체 검증 ----------

    @Test
    @DisplayName("V0 — 앱의 Redis 연결이 프록시를 경유하고, 정상 상태에서는 캐시가 채워진다")
    void proxyIsActuallyOnThePath() throws IOException {
        assertThat(redisProxy.getUpstream())
                .as("프록시의 upstream 이 Redis 컨테이너여야 한다")
                .isEqualTo("redis:" + REDIS_PORT);

        queryService.getProduct(productId);
        assertThat(readCachedProductKeyCount())
                .as("정상 경로에서 Redis 에 캐시 키가 실제로 적재돼야 한다 — 아니면 이후 장애 주입이 무의미하다")
                .isPositive();

        // 프록시를 끊으면 같은 연결이 실패해야 한다 = 앱이 프록시를 통과한다는 증거.
        // 누적값이 아니라 '이 호출이 만든 증가분'을 본다 — 앞선 테스트의 잔여로 통과하면
        // 프록시 우회를 못 잡는다.
        FallbackSnapshot before = captureFallback();
        clearInvocations(productRepository);
        redisProxy.disable();

        double staleBefore = metricCount("cache_stale_served_total", DETAIL_CACHE);

        assertThat(queryService.getProduct(productId))
                .as("프록시 차단 후에도 조회는 성공해야 한다(fail-open)")
                .isNotNull();

        assertThat(before.deltaOf(DETAIL_CACHE, "get"))
                .as("프록시를 끊었는데 product 캐시의 get fallback 이 늘지 않으면 앱이 프록시를 우회하고 있다")
                .isEqualTo(1.0);
        // 직전 정상 조회가 남긴 Pod 사본으로 응답한다(ADR-0046 D4). DB 는 치지 않는다.
        verify(productRepository, times(0)).findById(productId);
        assertThat(metricCount("cache_stale_served_total", DETAIL_CACHE) - staleBefore).isEqualTo(1.0);
    }

    // ---------- V1 · V2: 연결 거부 ----------

    @Test
    @DisplayName("V1 — 연결 거부 상태에서 상세·목록 조회가 DB 값을 반환하고 get fallback 메트릭이 노출된다")
    void connectionRefused_readsFallBackToDatabase() throws IOException {
        FallbackSnapshot before = captureFallback();
        redisProxy.disable();

        ProductDetailDto detail = queryService.getProduct(productId);
        assertThat(detail.name()).isEqualTo("스마트폰");
        assertThat(detail.stock()).isEqualTo(100);

        Page<ProductListDto> list = queryService.getProducts(null, DEFAULT_PAGE);
        assertThat(list.getTotalElements()).isEqualTo(1);

        assertThat(prometheusBody())
                .as("cache_fallback_total{operation=\"get\"} 가 노출돼야 한다")
                .containsPattern("cache_fallback_total\\{[^}]*operation=\"get\"[^}]*\\}");
        assertThat(before.deltaOf(DETAIL_CACHE, "get"))
                .as("상세 조회 1회는 product 캐시로 집계돼야 한다")
                .isEqualTo(1.0);
        assertThat(before.deltaOf(LIST_CACHE, "get"))
                .as("목록 조회 1회는 products 캐시로 집계돼야 한다 — 라벨이 뭉개지면 여기서 걸린다")
                .isEqualTo(1.0);
        // 위 둘과 달리 '정확히 1' 이 아니라 '1 이상' 이다. 재고 캐시는 상세 경로에서 product 캐시
        // 다음에 오므로, 앞선 테스트가 남긴 Lettuce 재연결 상태에 따라 한 요청이 2회 실패로
        // 집계되는 것을 실측했다(단독 실행 1 · 전체 실행 2). 여기서 고정하려는 계약은
        // "재고 캐시가 fail-open 경로에 있다" 이지 Lettuce 의 재시도 횟수가 아니다.
        // 누적값이 아니라 증가분이므로 경계 소실(0)은 그대로 잡힌다.
        assertThat(before.deltaOf(STOCK_CACHE, "get"))
                .as("""
                        재고 캐시(ADR-0026 D2)도 같은 fail-open 을 타야 한다. 여기가 0 이면 재고 조회가
                        캐시를 안 거치거나(경계 소실), errorHandler 가 그 캐시에 안 붙은 것이다.
                        전자면 detail 의 DB 왕복이 되살아난 것이고 후자면 Redis 장애가 500 으로 샌다.""")
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("V2 — 연결 거부 상태에서 GET /api/v1/products/{id} 가 200 이다 (5xx 아님)")
    void connectionRefused_httpStaysSuccessful() throws IOException {
        redisProxy.disable();

        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/products/" + productId, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("스마트폰");
    }

    // ---------- V3: 무응답 ----------

    @Test
    @DisplayName("V3 — 무응답 Redis 에서도 조회가 3.5s 이내에 끝나고 두 캐시의 get·put fallback 이 모두 발동한다")
    void unresponsiveRedis_isBoundedByCommandTimeout() throws IOException {
        // 정상 경로로 한 번 예열한다 — 첫 호출의 JIT/Hibernate/HTTP 초기화 비용(~600ms)이
        // 타임아웃 상한 측정에 섞이면 무엇을 재는지 알 수 없게 된다.
        queryService.getProduct(productId);
        // 예열이 남긴 Redis 값과 Pod 사본을 지운다. 사본이 있으면 장애 중 get 뒤에 적재(put)가 없다(ADR-0046 D4).
        cleanCaches(cacheManager);

        FallbackSnapshot before = captureFallback();
        Timeout toxic = redisProxy.toxics().timeout(TIMEOUT_TOXIC, ToxicDirection.DOWNSTREAM, 0);
        try {
            long startedAt = System.nanoTime();
            ProductDetailDto detail = queryService.getProduct(productId);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            Duration commandTimeout = ((LettuceConnectionFactory) redisConnectionFactory)
                    .getClientConfiguration().getCommandTimeout();
            double detailGet = before.deltaOf(DETAIL_CACHE, "get");
            double detailPut = before.deltaOf(DETAIL_CACHE, "put");
            double stockGet = before.deltaOf(STOCK_CACHE, "get");
            double stockPut = before.deltaOf(STOCK_CACHE, "put");
            String observation = "elapsed=%dms, commandTimeout=%dms, product/get=%s, product/put=%s, "
                    .formatted(elapsed.toMillis(), commandTimeout.toMillis(), detailGet, detailPut)
                    + "productStock/get=%s, productStock/put=%s".formatted(stockGet, stockPut);
            System.out.println("Redis V3 observation: " + observation);

            assertThat(detail.name()).as(observation).isEqualTo("스마트폰");
            assertThat(elapsed)
                    .as("""
                            무응답 Redis 상세 조회는 3500ms 미만이어야 한다. 관측: %s
                            상세 조회는 product + productStock 두 캐시를 탄다(ADR-0026 D2).
                            각 get·put이 timeout 500ms를 소비하면 4회 예산은 2000ms다.
                            이 경과에는 DB 조회·JVM 정지·runner 경합 등도 섞이므로 초과 원인을 단정하지 않는다.
                            timeout 변경, 캐시 경로 변화, DB/JVM/runner 지연을 함께 확인한다.
                            경로 카운터는 fallback 횟수이며 timeout 원인이나 시간 기여를 직접 증명하지 않는다.""",
                            observation)
                    // 여유 1500ms 는 부하 잡음 몫이다. 기존 D-046의 3500ms bound를 유지한다.
                    // 경로 소실은 시간만으로 가르지 않고 아래 요청별 증가분으로 확인한다.
                    .isLessThan(Duration.ofMillis(3500));

            // 누적값 단언이면 put 경로가 아예 안 타도 앞선 테스트 잔여로 통과한다.
            assertThat(detailGet).as("product get fallback: %s", observation).isEqualTo(1.0);
            assertThat(detailPut).as("product put fallback: %s", observation).isEqualTo(1.0);
            // 재고 캐시는 재연결 상태에 따른 이중 집계를 허용한다(V1의 관측 근거 참조).
            assertThat(stockGet).as("productStock get fallback: %s", observation)
                    .isGreaterThanOrEqualTo(1.0);
            assertThat(stockPut).as("productStock put fallback: %s", observation)
                    .isGreaterThanOrEqualTo(1.0);
        } finally {
            toxic.remove();
        }
    }

    // ---------- V4: evict 가시화 ----------

    @Test
    @DisplayName("V4 — 연결 거부 상태에서 상품 수정은 DB 에 커밋되고 evict fallback 이 기록된다")
    void connectionRefused_evictFailureIsVisibleButCommitSucceeds() throws IOException {
        FallbackSnapshot before = captureFallback();
        redisProxy.disable();

        commandService.update(productId,
                new UpdateProductCommand(categoryId, "갤럭시", "수정됨", 900_000L, null));

        healRedis();
        EntityManager em = emf.createEntityManager();
        try {
            Product persisted = em.find(Product.class, productId);
            assertThat(persisted.getName())
                    .as("캐시 무효화가 실패해도 DB 트랜잭션은 커밋돼야 한다")
                    .isEqualTo("갤럭시");
            assertThat(persisted.getPrice()).isEqualTo(900_000L);
        } finally {
            em.close();
        }

        assertThat(prometheusBody())
                .as("evict 실패는 조용히 넘기지 않는다 — stale 창이 열렸다는 신호다")
                .containsPattern("cache_fallback_total\\{[^}]*operation=\"evict\"[^}]*\\}")
                .containsPattern("cache_fallback_total\\{[^}]*operation=\"clear\"[^}]*\\}");
        // update 는 상세를 키 단위로(@CacheEvict key), 목록을 통째로(allEntries=true) 무효화한다.
        // allEntries 는 Spring 이 handleCacheEvictError 가 아니라 handleCacheClearError 로 보낸다 —
        // 두 콜백을 한 값으로 뭉개면 어느 캐시가 stale 인지 구분되지 않는다.
        assertThat(before.deltaOf(DETAIL_CACHE, "evict"))
                .as("상세 캐시(product) 키 단위 무효화 1회")
                .isEqualTo(1.0);
        assertThat(before.deltaOf(LIST_CACHE, "clear"))
                .as("목록 캐시(products) allEntries 무효화 1회 — clear 콜백으로 온다")
                .isEqualTo(1.0);
    }

    // ---------- V5: put 실패의 DB 부하 전이 ----------

    @Test
    @DisplayName("V5 — 캐시가 죽어 있어도 동일 상품 N회 조회는 DB 를 1회만 친다 (첫 적재가 Pod 사본이 된다, ADR-0046 D4)")
    void cacheOutage_loadsOnceThenServesPodCopy() throws IOException {
        int repeats = 5;
        FallbackSnapshot before = captureFallback();
        double staleBefore = metricCount("cache_stale_served_total", DETAIL_CACHE);
        redisProxy.disable();
        clearInvocations(productRepository);

        for (int i = 0; i < repeats; i++) {
            assertThat(queryService.getProduct(productId).name()).isEqualTo("스마트폰");
        }

        // D-109 전에는 repeats 회였다. Redis 에 못 넣은 값도 Pod 사본으로 남아 다음 요청에 나간다.
        verify(productRepository, times(1)).findById(productId);
        assertThat(before.deltaOf(DETAIL_CACHE, "get")).isEqualTo((double) repeats);
        assertThat(before.deltaOf(DETAIL_CACHE, "put")).isEqualTo(1.0);
        assertThat(metricCount("cache_stale_served_total", DETAIL_CACHE) - staleBefore)
                .isEqualTo((double) repeats - 1);
    }

    @Test
    @DisplayName("V6 — 장애 중 허용 범위를 넘은 Pod 사본은 내지 않는다 (정보 30분, 재고 5초)")
    void cacheOutage_doesNotServeCopiesBeyondLimit() throws IOException {
        assertThat(queryService.getProduct(productId).stock()).isEqualTo(100);
        updateStockDirectly(7);
        redisProxy.disable();
        clearInvocations(productRepository);

        // 실제 시간도 흐르므로(예열 뒤 1초 안팎) 경계 가까이 대지 않는다.
        clock.advance(Duration.ofSeconds(2));
        ProductDetailDto withinLimit = queryService.getProduct(productId);
        assertThat(withinLimit.stock()).as("재고 사본은 5초 안이면 낸다").isEqualTo(100);
        verify(productRepository, times(0)).findById(productId);

        clock.advance(Duration.ofSeconds(3));
        assertThat(queryService.getProduct(productId).stock())
                .as("5초를 넘긴 재고 사본은 장애 중에도 내지 않는다(ADR-0026 D2)")
                .isEqualTo(7);
        verify(productRepository, times(0)).findById(productId);

        clock.advance(Duration.ofMinutes(30));
        assertThat(queryService.getProduct(productId).name()).isEqualTo("스마트폰");
        verify(productRepository, times(1)).findById(productId);
    }

    @Test
    @DisplayName("V5-대조 — 캐시가 살아 있으면 동일 상품 N회 조회에도 DB 는 1회만 친다")
    void healthyCache_absorbsRepeatedReads() {
        int repeats = 5;
        for (int i = 0; i < repeats; i++) {
            assertThat(queryService.getProduct(productId).name()).isEqualTo("스마트폰");
        }

        // 양성 대조군: V5 의 N회가 '원래 그런 것'이 아니라 캐시 유실의 결과임을 고정한다.
        verify(productRepository, times(1)).findById(productId);
        verify(productRepository, atLeast(0)).findById(productId);
    }

    // ---------- helpers ----------

    private String prometheusBody() {
        ResponseEntity<String> response = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return Optional.ofNullable(response.getBody()).orElse("");
    }

    /**
     * 장애 주입 직전의 {@code cache_fallback_total} 기준값.
     * <p>메트릭은 Spring 컨텍스트 수명 동안 누적되고 테스트 실행 순서는 고정이 아니므로,
     * 누적값을 단언하면 앞선 테스트의 잔여로 통과해버린다. 각 시나리오는 <b>자기 증가분</b>만 본다.
     */
    private final class FallbackSnapshot {
        private final java.util.Map<String, Double> baseline = new java.util.HashMap<>();

        private FallbackSnapshot() {
            for (String cache : List.of(DETAIL_CACHE, LIST_CACHE, STOCK_CACHE, "unknown")) {
                for (String operation : List.of("get", "put", "evict", "clear")) {
                    baseline.put(key(cache, operation), fallbackCount(cache, operation));
                }
            }
        }

        /**
         * {@code cache} 라벨까지 함께 본다 — operation 만 보면 핸들러가 모든 이벤트를
         * {@code cache="unknown"} 으로 기록해도 단언이 통과해 캐시 이름 라벨 계약이 무너진다.
         */
        double deltaOf(String cache, String operation) {
            return fallbackCount(cache, operation) - baseline.getOrDefault(key(cache, operation), 0.0);
        }

        private static String key(String cache, String operation) {
            return cache + "/" + operation;
        }
    }

    private FallbackSnapshot captureFallback() {
        return new FallbackSnapshot();
    }

    /** {@code cache_fallback_total} 중 (cache, operation) 시계열 값. 없으면 0. */
    private double fallbackCount(String cache, String operation) {
        return prometheusBody().lines()
                .filter(line -> line.startsWith("cache_fallback_total{"))
                .filter(line -> line.contains("cache=\"" + cache + "\""))
                .filter(line -> line.contains("operation=\"" + operation + "\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }

    /** 새 캐시 지표(ADR-0046)의 {@code cache} 시계열 값. 없으면 0. */
    private double metricCount(String metric, String cache) {
        return prometheusBody().lines()
                .filter(line -> line.startsWith(metric + "{"))
                .filter(line -> line.contains("cache=\"" + cache + "\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }

    private void updateStockDirectly(int stock) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            em.createNativeQuery("UPDATE inventories SET stock = ? WHERE product_id = ?")
                    .setParameter(1, stock)
                    .setParameter(2, productId)
                    .executeUpdate();
            em.getTransaction().commit();
        } finally {
            em.close();
        }
    }

    private long readCachedProductKeyCount() {
        try (var connection = redisConnectionFactory.getConnection()) {
            return connection.keyCommands().keys("cache:v2:product::*".getBytes()).size();
        }
    }

    private void flushRedis() {
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    /**
     * 주입한 장애를 모두 걷어낸다 — 테스트 간 순서 의존을 없애는 계약.
     * <p>프록시를 되살려도 Lettuce 의 공유 연결은 즉시 복구되지 않는다. 뒤이은 스캐폴딩
     * (flushDb·KEYS)은 {@code CacheErrorHandler} 를 거치지 않는 직접 호출이라 그대로 터진다 —
     * 실제 PING 이 통할 때까지 기다려 <b>테스트 준비 실패를 계약 실패로 오인하지 않게</b> 한다.
     */
    private void healRedis() {
        try {
            redisProxy.enable();
            var existing = redisProxy.toxics().get(TIMEOUT_TOXIC);
            if (existing != null) {
                existing.remove();
            }
        } catch (IOException e) {
            // toxic 이 없으면 404 로 IOException 이 난다 — 원복 목적상 정상
        }
        awaitRedisReachable();
    }

    private void awaitRedisReachable() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .ignoreExceptions()
                .untilAsserted(() -> {
                    try (var connection = redisConnectionFactory.getConnection()) {
                        assertThat(connection.ping()).isNotNull();
                    }
                });
    }
    @Test
    @DisplayName("D2 — 장애 중 같은 키 동시 조회는 한 로딩에 묶여 모두 200 이고, 다른 키는 상한에 거절된다")
    void outageCoalescesSameKeyAndBoundsOtherKeys() throws Exception {
        Long otherProductId = commandService.create(
                new CreateProductCommand(categoryId, "태블릿", "설명", 500_000L, null, 10)).id();
        cleanCaches(cacheManager);
        clearInvocations(productRepository);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(5);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return invocation.callRealMethod();
        }).when(productRepository).findById(productId);
        redisProxy.disable();
        double coalescedBefore = metricCount("cache_load_coalesced_total", DETAIL_CACHE);
        try {
            var admitted = executor.submit(() -> queryService.getProduct(productId));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var waiters = new java.util.ArrayList<java.util.concurrent.Future<ResponseEntity<String>>>();
            for (int i = 0; i < 4; i++) waiters.add(executor.submit(() ->
                    restTemplate.getForEntity("/api/v1/products/" + productId, String.class)));
            // 기다림 상한(3초) 안에 넷 모두 리더의 로딩에 붙었는지 확인한 뒤 놓는다.
            Awaitility.await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50)).until(() ->
                    metricCount("cache_load_coalesced_total", DETAIL_CACHE) - coalescedBefore >= 4);

            ResponseEntity<String> other =
                    restTemplate.getForEntity("/api/v1/products/" + otherProductId, String.class);
            assertThat(other.getStatusCode()).as("다른 키의 miss 는 상한을 지나야 한다(ADR-0037)")
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(other.getBody()).contains("PRD-005");

            var pool = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
            assertThat(pool.getHikariPoolMXBean().getActiveConnections())
                    .as("기다리는 요청은 커넥션을 잡지 않는다 — 리더 하나만 잡는다").isEqualTo(1);

            release.countDown();
            assertThat(admitted.get(10, TimeUnit.SECONDS).id()).isEqualTo(productId);
            for (var response : waiters) {
                var result = response.get(10, TimeUnit.SECONDS);
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
                assertThat(result.getBody()).contains("스마트폰");
            }
            verify(productRepository, times(1)).findById(productId);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            reset(productRepository);
            healRedis();
        }
        cleanCaches(cacheManager);
        assertThat(queryService.getProduct(productId).id()).isEqualTo(productId);
        clearInvocations(productRepository);
        assertThat(queryService.getProduct(productId).id()).isEqualTo(productId);
        verify(productRepository, times(0)).findById(productId);
    }

    @Test
    @DisplayName("D2 — Redis 가 정상일 때도 같은 키 동시 miss 는 DB 를 1회만 친다")
    void healthyRedisCoalescesSameKeyMisses() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(5);
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return invocation.callRealMethod();
        }).when(productRepository).findById(productId);
        double coalescedBefore = metricCount("cache_load_coalesced_total", DETAIL_CACHE);
        try {
            var results = new java.util.ArrayList<java.util.concurrent.Future<ProductDetailDto>>();
            results.add(executor.submit(() -> queryService.getProduct(productId)));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            for (int i = 0; i < 4; i++) results.add(executor.submit(() -> queryService.getProduct(productId)));
            Awaitility.await().atMost(Duration.ofSeconds(2)).pollInterval(Duration.ofMillis(50)).until(() ->
                    metricCount("cache_load_coalesced_total", DETAIL_CACHE) - coalescedBefore >= 4);
            release.countDown();

            for (var result : results) assertThat(result.get(10, TimeUnit.SECONDS).name()).isEqualTo("스마트폰");
            verify(productRepository, times(1)).findById(productId);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            reset(productRepository);
        }
    }

    @Test
    @DisplayName("D3 — soft 를 지난 재고의 갱신이 상한에 거절되면 hard 안에서는 기존 값이 200 으로 나간다")
    void rejectedStockRefreshServesPreviousValueUntilHard() throws Exception {
        Long otherProductId = commandService.create(
                new CreateProductCommand(categoryId, "태블릿", "설명", 500_000L, null, 10)).id();
        assertThat(queryService.getProduct(productId).stock()).isEqualTo(100);
        updateStockDirectly(7);
        cacheManager.getCache(DETAIL_CACHE).evict(otherProductId);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return invocation.callRealMethod();
        }).when(productRepository).findById(otherProductId);
        double rejectedBefore = metricCount("cache_refresh_rejected_total", STOCK_CACHE);
        try {
            var holder = executor.submit(() -> queryService.getProduct(otherProductId)); // 상한(permit 1)을 잡는다
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            // soft 3초 < 나이 < hard 5초. 실제 시간도 흐르므로 soft 바로 위에 둔다.
            clock.advance(Duration.ofMillis(3_100));
            ResponseEntity<String> refreshed =
                    restTemplate.getForEntity("/api/v1/products/" + productId, String.class);
            assertThat(refreshed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(refreshed.getBody()).as("거절된 갱신 대신 기존 재고가 나간다").contains("\"stock\":100");
            assertThat(metricCount("cache_refresh_rejected_total", STOCK_CACHE) - rejectedBefore).isEqualTo(1.0);

            clock.advance(Duration.ofMillis(1_900)); // hard 5초 이상. Redis 에는 아직 남아 있다
            ResponseEntity<String> expired =
                    restTemplate.getForEntity("/api/v1/products/" + productId, String.class);
            assertThat(expired.getStatusCode()).as("hard 를 넘으면 기존 값을 내지 않고 상한 거절이 그대로 보인다")
                    .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);

            release.countDown();
            assertThat(holder.get(10, TimeUnit.SECONDS).id()).isEqualTo(otherProductId);
            assertThat(queryService.getProduct(productId).stock()).isEqualTo(7);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            reset(productRepository);
        }
    }

    @Test
    @DisplayName("롤링 호환 — 옛 접두사(cache:)의 값은 읽지 않는다")
    void legacyPrefixIsIgnored() {
        try (var connection = redisConnectionFactory.getConnection()) {
            connection.stringCommands().set(("cache:product::" + productId).getBytes(), "\"legacy\"".getBytes());
        }

        assertThat(queryService.getProduct(productId).name()).isEqualTo("스마트폰");
        verify(productRepository, times(1)).findById(productId);
    }

    @Test
    void healthyCacheHitBypassesSaturatedDatabasePermit() throws Exception {
        queryService.getProducts(null, DEFAULT_PAGE); // warm only the list, leaving detail a miss
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var executor = Executors.newSingleThreadExecutor();
        doAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test release timed out");
            return invocation.callRealMethod();
        }).when(productRepository).findById(productId);
        try {
            var miss = executor.submit(() -> queryService.getProduct(productId));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(queryService.getProducts(null, DEFAULT_PAGE).getContent())
                    .extracting(ProductListDto::id).containsExactly(productId);
            release.countDown();
            assertThat(miss.get(10, TimeUnit.SECONDS).id()).isEqualTo(productId);
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            reset(productRepository);
        }
    }
}
