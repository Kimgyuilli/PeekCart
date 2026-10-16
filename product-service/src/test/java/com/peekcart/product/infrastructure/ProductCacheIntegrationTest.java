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
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import java.util.List;

import com.peekcart.product.application.dto.UpdateProductCommand;
import com.peekcart.support.SharedContainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(SharedContainers.class)
@DisplayName("상품 캐싱 통합 테스트 (Testcontainers Redis)")
class ProductCacheIntegrationTest extends AbstractIntegrationTest {

    private static final PageRequest DEFAULT_PAGE = PageRequest.of(0, 10);
    private static final String LIST_CACHE_KEY = "list:null:0:10:id: ASC";

    @Autowired org.springframework.test.web.servlet.MockMvc mockMvc;
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
    @Test
    void everyAllowedSortHasIndependentCacheAndStablePageBoundaries() {
        commandService.update(productId, new UpdateProductCommand(categoryId, "B", "설명", 300L, null));
        Long second = commandService.create(new CreateProductCommand(categoryId, "A", "설명", 100L, null, 1)).id();
        Long third = commandService.create(new CreateProductCommand(categoryId, "C", "설명", 200L, null, 1)).id();
        Long fourth = commandService.create(new CreateProductCommand(categoryId, "A", "설명", 200L, null, 1)).id();
        var defaultIds = List.of(productId, second, third, fourth);
        record Case(Sort sort, List<Long> ids) {}
        var cases = List.of(
                new Case(Sort.by("id"), defaultIds),
                new Case(Sort.by("id").descending(), List.of(fourth, third, second, productId)),
                new Case(Sort.by("price"), List.of(second, third, fourth, productId)),
                new Case(Sort.by("price").descending(), List.of(productId, third, fourth, second)),
                new Case(Sort.by("name"), List.of(second, fourth, productId, third)),
                new Case(Sort.by("name").descending(), List.of(third, productId, second, fourth)));
        for (Case item : cases) {
            for (boolean sortedFirst : new boolean[]{true, false}) {
                cleanCaches(cacheManager);
                var sortedPage = PageRequest.of(0, 10, item.sort());
                if (sortedFirst) queryService.getProducts(null, sortedPage);
                else queryService.getProducts(null, DEFAULT_PAGE);
                assertThat(queryService.getProducts(null, DEFAULT_PAGE).getContent())
                        .extracting(ProductListDto::id).containsExactlyElementsOf(defaultIds);
                assertThat(queryService.getProducts(null, sortedPage).getContent())
                        .extracting(ProductListDto::id).containsExactlyElementsOf(item.ids());
                for (int page = 0; page < 2; page++) {
                    assertThat(queryService.getProducts(null, PageRequest.of(page, 2, item.sort())).getContent())
                            .extracting(ProductListDto::id)
                            .containsExactlyElementsOf(item.ids().subList(page * 2, page * 2 + 2));
                }
            }
        }
    }
    @Test
    void invalidSortIsBadRequestBeforeDatabaseQuery() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/products")
                        .param("sort", "noSuchField,desc"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("SYS-003"));
    }
}
