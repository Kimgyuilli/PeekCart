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
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Redis 장애 시 조회 캐시 fail-open 검증 (L-006, 구현 ⑤ P5/P6 · 계획 §5 V0~V5).
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
@Testcontainers
@Import(IntegrationTestConfig.class)
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

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    /**
     * Redis 는 {@code @ServiceConnection} 을 <b>붙이지 않는다</b>. 붙이면 Spring 이 컨테이너의
     * mapped port 로 직결돼 프록시를 우회하고, 장애 주입이 전부 무효가 된다.
     */
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7")
            .withExposedPorts(REDIS_PORT)
            .withNetwork(NETWORK)
            .withNetworkAliases("redis");

    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer("ghcr.io/shopify/toxiproxy:2.5.0")
            .withNetwork(NETWORK);

    private static Proxy redisProxy;

    static {
        // @Container 가 아니라 static 블록에서 띄운다 — @DynamicPropertySource 가 평가되기 전에
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

    @DynamicPropertySource
    static void redisThroughProxy(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", TOXIPROXY::getHost);
        registry.add("spring.data.redis.port", () -> TOXIPROXY.getMappedPort(PROXY_LISTEN_PORT));
    }

    @Autowired ProductQueryService queryService;
    @Autowired ProductCommandService commandService;
    @Autowired TestRestTemplate restTemplate;
    @Autowired RedisConnectionFactory redisConnectionFactory;

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
        // 프록시 우회를 못 잡는다(diff 리뷰 #2).
        FallbackSnapshot before = captureFallback();
        clearInvocations(productRepository);
        redisProxy.disable();

        assertThat(queryService.getProduct(productId))
                .as("프록시 차단 후에도 조회는 성공해야 한다(fail-open)")
                .isNotNull();

        assertThat(before.deltaOf(DETAIL_CACHE, "get"))
                .as("프록시를 끊었는데 product 캐시의 get fallback 이 늘지 않으면 앱이 프록시를 우회하고 있다")
                .isEqualTo(1.0);
        verify(productRepository, times(1))
                .findById(productId);   // 캐시가 살아 있었다면 0회였다
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
    @DisplayName("V3 — 무응답 Redis 에서도 조회가 1.5s 이내에 끝나고 get·put fallback 이 모두 발동한다")
    void unresponsiveRedis_isBoundedByCommandTimeout() throws IOException {
        // 정상 경로로 한 번 예열한다 — 첫 호출의 JIT/Hibernate/HTTP 초기화 비용(~600ms)이
        // 타임아웃 상한 측정에 섞이면 무엇을 재는지 알 수 없게 된다.
        queryService.getProduct(productId);

        FallbackSnapshot before = captureFallback();
        Timeout toxic = redisProxy.toxics().timeout(TIMEOUT_TOXIC, ToxicDirection.DOWNSTREAM, 0);
        try {
            long startedAt = System.nanoTime();
            ProductDetailDto detail = queryService.getProduct(productId);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

            assertThat(detail.name()).isEqualTo("스마트폰");
            assertThat(elapsed)
                    .as("""
                            상세 조회는 캐시를 <b>둘</b> 탄다(product + productStock, ADR-0026 D2).
                            무응답 Redis 에서는 각 캐시가 get 500ms + put 500ms 를 소비하므로
                            타임아웃 예산이 4회 = 2000ms 다 — 재고 캐시 도입 전에는 2회 = 1000ms 였다.
                            이 배가는 D2 가 실제로 치르는 비용이고 ADR-0026 Consequences 에 적혀 있다.
                            상한이 깨지면 timeout 설정이 지워졌거나 캐시가 하나 더 늘어난 것이다.""")
                    .isLessThan(Duration.ofMillis(2600));

            // 한 요청이 get·put 두 경로 '모두에서' 타임아웃을 맞았음을 증가분으로 고정한다.
            // 누적값 단언이면 put 경로가 아예 안 타도 앞선 테스트 잔여로 통과한다(diff 리뷰 #2).
            assertThat(before.deltaOf(DETAIL_CACHE, "get")).isEqualTo(1.0);
            assertThat(before.deltaOf(DETAIL_CACHE, "put")).isEqualTo(1.0);
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
    @DisplayName("V5 — 캐시가 죽어 있으면 동일 상품 N회 조회가 DB 를 N회 친다 (put 실패는 무해하지 않다)")
    void cacheOutage_transfersLoadToDatabase() throws IOException {
        int repeats = 5;
        FallbackSnapshot before = captureFallback();
        redisProxy.disable();
        clearInvocations(productRepository);

        for (int i = 0; i < repeats; i++) {
            assertThat(queryService.getProduct(productId).name()).isEqualTo("스마트폰");
        }

        // 상세 조회의 재고 조회(InventoryRepository)는 캐시와 무관하게 매 요청 발생하므로
        // 부하 증거에서 제외하고, 캐시가 살아 있었다면 1회로 줄었을 findById 만 센다.
        verify(productRepository, times(repeats)).findById(productId);
        assertThat(before.deltaOf(DETAIL_CACHE, "get")).isEqualTo((double) repeats);
        assertThat(before.deltaOf(DETAIL_CACHE, "put")).isEqualTo((double) repeats);
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
            for (String cache : List.of(DETAIL_CACHE, LIST_CACHE, "unknown")) {
                for (String operation : List.of("get", "put", "evict", "clear")) {
                    baseline.put(key(cache, operation), fallbackCount(cache, operation));
                }
            }
        }

        /**
         * {@code cache} 라벨까지 함께 본다 — operation 만 보면 핸들러가 모든 이벤트를
         * {@code cache="unknown"} 으로 기록해도 단언이 통과해 P3 의 라벨 계약이 무너진다.
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

    private long readCachedProductKeyCount() {
        try (var connection = redisConnectionFactory.getConnection()) {
            return connection.keyCommands().keys("cache:product::*".getBytes()).size();
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
}
