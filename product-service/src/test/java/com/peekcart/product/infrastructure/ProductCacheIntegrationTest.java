package com.peekcart.product.infrastructure;

import com.peekcart.product.application.ProductCommandService;
import com.peekcart.product.application.ProductQueryService;
import com.peekcart.product.application.dto.CreateProductCommand;
import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.application.dto.ProductListDto;
import com.peekcart.product.domain.model.Category;
import com.peekcart.support.AbstractIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import com.peekcart.product.application.dto.UpdateProductCommand;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("상품 캐싱 통합 테스트 (Testcontainers Redis)")
class ProductCacheIntegrationTest extends AbstractIntegrationTest {

    private static final PageRequest DEFAULT_PAGE = PageRequest.of(0, 10);
    private static final String LIST_CACHE_KEY = "list:null:0:10";

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired ProductQueryService queryService;
    @Autowired ProductCommandService commandService;
    @Autowired CacheManager cacheManager;

    private Long categoryId;
    private Long productId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        cleanCaches(cacheManager);

        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Category category = Category.create("전자기기", null);
        em.persist(category);
        em.flush();
        categoryId = category.getId();
        em.getTransaction().commit();
        em.close();

        ProductDetailDto created = commandService.create(
                new CreateProductCommand(categoryId, "스마트폰", "설명", 1_000_000L, null, 100));
        productId = created.id();

        // create가 목록 캐시를 evict하므로, 테스트 시작 전 캐시 상태를 초기화
        cleanCaches(cacheManager);
    }

    @Test
    @DisplayName("상세 조회: 첫 호출 시 캐시 미스 → 두 번째 호출 시 캐시 적중")
    void getProduct_cacheHit() {
        assertThat(cacheManager.getCache("product").get(productId)).isNull();

        ProductDetailDto first = queryService.getProduct(productId);
        assertThat(cacheManager.getCache("product").get(productId)).isNotNull();

        ProductDetailDto second = queryService.getProduct(productId);
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(second.name()).isEqualTo(first.name());
    }

    @Test
    @DisplayName("목록 조회: 첫 호출 시 캐시 미스 → 두 번째 호출 시 캐시 적중")
    void getProducts_cacheHit() {
        String listKey = LIST_CACHE_KEY;
        assertThat(cacheManager.getCache("products").get(listKey)).isNull();

        Page<ProductListDto> first = queryService.getProducts(null, DEFAULT_PAGE);
        assertThat(cacheManager.getCache("products").get(listKey)).isNotNull();

        Page<ProductListDto> second = queryService.getProducts(null, DEFAULT_PAGE);
        assertThat(second.getTotalElements()).isEqualTo(first.getTotalElements());
    }

    @Test
    @DisplayName("상품 수정 시 상세 캐시와 목록 캐시가 모두 무효화된다")
    void update_evictsBothCaches() {
        queryService.getProduct(productId);
        queryService.getProducts(null, DEFAULT_PAGE);
        assertThat(cacheManager.getCache("product").get(productId)).isNotNull();
        assertThat(cacheManager.getCache("products").get(LIST_CACHE_KEY)).isNotNull();

        commandService.update(productId,
                new UpdateProductCommand(categoryId, "갤럭시", "수정됨", 900_000L, null));

        assertThat(cacheManager.getCache("product").get(productId)).isNull();
        assertThat(cacheManager.getCache("products").get(LIST_CACHE_KEY)).isNull();

        ProductDetailDto refreshed = queryService.getProduct(productId);
        assertThat(refreshed.name()).isEqualTo("갤럭시");
        assertThat(refreshed.price()).isEqualTo(900_000L);
    }

    @Test
    @DisplayName("상품 삭제 시 상세 캐시와 목록 캐시가 모두 무효화된다")
    void delete_evictsBothCaches() {
        queryService.getProduct(productId);
        queryService.getProducts(null, DEFAULT_PAGE);
        assertThat(cacheManager.getCache("product").get(productId)).isNotNull();
        assertThat(cacheManager.getCache("products").get(LIST_CACHE_KEY)).isNotNull();

        commandService.delete(productId);

        assertThat(cacheManager.getCache("product").get(productId)).isNull();
        assertThat(cacheManager.getCache("products").get(LIST_CACHE_KEY)).isNull();
    }

    @Test
    @DisplayName("상품 등록 시 목록 캐시가 무효화된다")
    void create_evictsListCache() {
        queryService.getProducts(null, DEFAULT_PAGE);
        assertThat(cacheManager.getCache("products").get(LIST_CACHE_KEY)).isNotNull();

        commandService.create(
                new CreateProductCommand(categoryId, "태블릿", "설명", 500_000L, null, 50));

        assertThat(cacheManager.getCache("products").get(LIST_CACHE_KEY)).isNull();

        Page<ProductListDto> refreshed = queryService.getProducts(null, DEFAULT_PAGE);
        assertThat(refreshed.getTotalElements()).isEqualTo(2);
    }
}
