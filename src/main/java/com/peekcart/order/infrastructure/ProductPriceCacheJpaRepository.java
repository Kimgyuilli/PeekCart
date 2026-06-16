package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.ProductPriceCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link ProductPriceCache} 엔티티에 대한 Spring Data JPA 리포지터리.
 */
public interface ProductPriceCacheJpaRepository extends JpaRepository<ProductPriceCache, Long> {

    @Query("SELECT c.unitPrice FROM ProductPriceCache c WHERE c.productId = :productId")
    Optional<Long> findUnitPriceByProductId(@Param("productId") Long productId);

    /**
     * 원자 upsert (stale-skip). 신규면 INSERT, 기존이면 더 높은 {@code version} 일 때만 갱신한다.
     * 단일 DB 문장이라 동시 삽입/갱신 경합에서도 PK 행 락으로 "더 높은 version 만 적용" 을 보장한다
     * (two-step update→insert 의 catch-밖 commit 위반 문제 회피, GW-2 #1).
     */
    @Modifying(clearAutomatically = true)
    @Query(value = "INSERT INTO product_price_cache (product_id, unit_price, source_version, updated_at) "
            + "VALUES (:productId, :unitPrice, :version, :now) "
            + "ON DUPLICATE KEY UPDATE "
            + "unit_price = IF(:version > source_version, :unitPrice, unit_price), "
            + "source_version = IF(:version > source_version, :version, source_version), "
            + "updated_at = IF(:version > source_version, :now, updated_at)", nativeQuery = true)
    void upsertIfNewer(@Param("productId") Long productId, @Param("unitPrice") long unitPrice,
                       @Param("version") long version, @Param("now") LocalDateTime now);
}
