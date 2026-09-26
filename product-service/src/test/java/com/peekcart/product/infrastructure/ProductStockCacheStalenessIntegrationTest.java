package com.peekcart.product.infrastructure;

import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.product.application.ProductCommandService;
import com.peekcart.product.application.ProductQueryService;
import com.peekcart.product.application.StockReservationService;
import com.peekcart.product.application.dto.CreateProductCommand;
import com.peekcart.product.domain.model.Category;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-026 / ADR-0026 — 재고 캐시의 <b>stale 상한이 TTL 로 유계</b>임을 강제하는 계약 테스트.
 *
 * <p><b>왜 이 축인가.</b> 쓰기 경로에 무효화를 배선하지 않는다는 결정(ADR-0026 D3) 때문에
 * 정확성의 상한을 주는 것은 <b>TTL 하나뿐</b>이다. 그 상한이 실제로 존재하는지를 시간 경과에
 * 따른 값 전이로 확인한다 — "캐시 엔트리가 있다" 는 이 결정에 대해 아무것도 증명하지 못한다.
 *
 * <p><b>TTL 을 1초로 주입하는 이유.</b> 운영 기본값 5초는 {@code CacheConfig} 가 소유하고
 * (ADR-0007), 여기서는 시간 축을 결정적으로 만들기 위해서만 줄인다. 값이 아니라
 * <b>"TTL 이 상한을 준다"는 성질</b>이 검증 대상이다.
 *
 * <p><b>false-green 차단.</b> TTL 을 매우 길게 바꾸면 {@link #stockCache_expiresAfterTtl}
 * 의 만료 후 단언이 red 가 된다 — 즉 이 테스트는 시간 의존이 실재해야만 통과한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 운영 기본값은 5s (CacheConfig). 시간 축을 결정적으로 만들기 위해서만 줄인다.
        "peekcart.cache.product-stock-ttl=1s"
})
@Import(SharedContainers.class)
@DisplayName("D-026 재고 캐시 stale 상한 계약")
class ProductStockCacheStalenessIntegrationTest extends AbstractIntegrationTest {

    private static final Duration TTL = Duration.ofSeconds(1);

    @Autowired ProductQueryService queryService;
    @Autowired ProductCommandService commandService;
    @Autowired StockReservationService reservationService;
    @Autowired CacheManager cacheManager;

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
        Long categoryId = category.getId();
        em.getTransaction().commit();
        em.close();

        productId = commandService.create(
                new CreateProductCommand(categoryId, "스마트폰", "설명", 1_000_000L, null, 100)).id();

        cleanCaches(cacheManager);
    }

    @Test
    @DisplayName("TTL 경과 전에는 구값 · 경과 후에는 신값 — stale 상한이 TTL 로 유계다 (P4)")
    void stockCache_expiresAfterTtl() {
        assertThat(queryService.getProduct(productId).stock()).isEqualTo(100);

        // 캐시를 우회해 DB 재고를 직접 바꾼다 — 애플리케이션 경로로 바꾸면
        // "캐시가 안 먹었을 뿐" 과 구분되지 않는다.
        updateStockDirectly(7);

        assertThat(queryService.getProduct(productId).stock())
                .as("TTL 경과 전에는 캐시의 구값이 보여야 한다 — stale 이 실재한다는 양성 확인")
                .isEqualTo(100);

        Awaitility.await("TTL 만료 후 신값")
                .atMost(TTL.plusSeconds(5))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(queryService.getProduct(productId).stock())
                        .as("""
                                TTL 이 지나면 DB 값으로 수렴해야 한다.
                                여기서 영영 100 이면 상한이 없는 것이고, 무효화가 없는 이 설계에서
                                그것은 stale 이 무기한이라는 뜻이다 (ADR-0026 D2/D3).""")
                        .isEqualTo(7));
    }

    @Test
    @DisplayName("예약으로 재고가 줄어도 TTL 안에는 구값이 보인다 — 쓰기 경로 무효화 없음이 결정이다 (P5)")
    void reservation_doesNotEvictStockCache() {
        assertThat(queryService.getProduct(productId).stock()).isEqualTo(100);

        reservationService.reserve(1L, UUID.randomUUID().toString(),
                List.of(new ReservedItemPayload(productId, 30)));

        assertThat(readStockDirectly())
                .as("예약은 DB 재고를 실제로 줄였다 (이 단언이 없으면 아래가 vacuous 하다)")
                .isEqualTo(70);

        assertThat(queryService.getProduct(productId).stock())
                .as("""
                        예약 직후에도 조회는 캐시의 구값(100)을 반환한다. 이것은 결함이 아니라
                        채택한 동작이다 (ADR-0026 D3) — 쓰기 경로는 캐시를 무효화하지 않는다.
                        예약/복구는 consumer 트랜잭션에 REQUIRED 로 참여하므로 @CacheEvict 는
                        커밋보다 먼저 돌고, 그 창의 재조회가 미커밋 구값을 되캐싱하면 stale 이
                        오히려 TTL 까지 고착된다 (ADR-0025 와 같은 함정).

                        이 단언이 red 라면 누군가 쓰기 경로에 무효화를 끼워 넣은 것이다.
                        그것을 하려면 먼저 ADR-0026 D3/D4 를 읽고 afterCommit 기준으로 설계해야 한다.""")
                .isEqualTo(100);
    }

    private void updateStockDirectly(int stock) {
        inTransaction(em -> em.createNativeQuery(
                        "UPDATE inventories SET stock = ? WHERE product_id = ?")
                .setParameter(1, stock)
                .setParameter(2, productId)
                .executeUpdate());
    }

    private int readStockDirectly() {
        EntityManager em = emf.createEntityManager();
        try {
            return ((Number) em.createNativeQuery(
                            "SELECT stock FROM inventories WHERE product_id = ?")
                    .setParameter(1, productId)
                    .getSingleResult()).intValue();
        } finally {
            em.close();
        }
    }

    private void inTransaction(java.util.function.Consumer<EntityManager> work) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            work.accept(em);
            em.getTransaction().commit();
        } catch (RuntimeException e) {
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            throw e;
        } finally {
            em.close();
        }
    }
}
