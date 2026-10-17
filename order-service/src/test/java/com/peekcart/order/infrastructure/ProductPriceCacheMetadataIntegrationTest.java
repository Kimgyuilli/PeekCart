package com.peekcart.order.infrastructure;

import com.peekcart.order.application.CartCommandService;
import com.peekcart.order.application.CartQueryService;
import com.peekcart.order.application.dto.AddCartItemCommand;
import com.peekcart.order.application.dto.UpdateCartItemCommand;
import com.peekcart.order.domain.model.ProductPriceCache;
import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(SharedContainers.class)
class ProductPriceCacheMetadataIntegrationTest extends AbstractIntegrationTest {
    @Autowired ProductPriceCacheRepository repository;
    @Autowired ProductPriceCacheJpaRepository jpa;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired CartCommandService cartCommand;
    @Autowired CartQueryService cartQuery;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        cleanDatabase();
    }

    @Test
    void higherVersionUpdatesWholeSnapshotAndTimestampWhileStaleAndEqualVersionsDoNothing() {
        apply(1L, 100L, 1L, "old", "ACTIVE");
        LocalDateTime oldTime = LocalDateTime.of(2020, 1, 1, 0, 0);
        jdbc.update("UPDATE product_price_cache SET updated_at=? WHERE product_id=1", oldTime);
        apply(1L, 200L, 3L, "new", "INACTIVE");
        ProductPriceCache expected = row(1L);
        assertThat(expected.getUpdatedAt()).isAfter(oldTime);
        apply(1L, 300L, 2L, "stale", "ACTIVE");
        apply(1L, 400L, 3L, "same-version", "SOLD_OUT");
        ProductPriceCache actual = row(1L);
        assertThat(actual.getUnitPrice()).isEqualTo(200L);
        assertThat(actual.getProductName()).isEqualTo("new");
        assertThat(actual.getProductStatus()).isEqualTo("INACTIVE");
        assertThat(actual.getUpdatedAt()).isEqualTo(expected.getUpdatedAt());
    }

    @Test
    void concurrentVersionsCannotMixFieldsOrRegress() throws Exception {
        var executor = Executors.newFixedThreadPool(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try {
            var first = CompletableFuture.runAsync(() -> { await(start); apply(2L, 110L, 11L, "v11", "ACTIVE"); }, executor);
            var second = CompletableFuture.runAsync(() -> { await(start); apply(2L, 120L, 12L, "v12", "SOLD_OUT"); }, executor);
            start.countDown();
            CompletableFuture.allOf(first, second).get(15, TimeUnit.SECONDS);
            var row = row(2L);
            assertThat(row.getSourceVersion()).isEqualTo(12L);
            assertThat(row.getUnitPrice()).isEqualTo(120L);
            assertThat(row.getProductName()).isEqualTo("v12");
            assertThat(row.getProductStatus()).isEqualTo("SOLD_OUT");
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void getAddAndUpdateAllComposeMetadataAndMissingRowKeepsItem() {
        apply(3L, 300L, 1L, "상품", "ACTIVE");
        var added = cartCommand.addItem(42L, new AddCartItemCommand(3L, 1));
        assertThat(added.items().get(0).productName()).isEqualTo("상품");
        Long itemId = cartQuery.getCart(42L).items().get(0).id();
        apply(3L, 400L, 2L, "단종 상품", "INACTIVE");
        var updated = cartCommand.updateItem(42L, itemId, new UpdateCartItemCommand(2));
        assertThat(updated.items().get(0).unitPrice()).isEqualTo(400L);
        assertThat(updated.items().get(0).available()).isFalse();
        jdbc.update("DELETE FROM product_price_cache WHERE product_id=3");
        var missing = cartQuery.getCart(42L).items();
        assertThat(missing).hasSize(1);
        assertThat(missing.get(0).quantity()).isEqualTo(2);
        assertThat(missing.get(0).unitPrice()).isNull();
        assertThat(missing.get(0).available()).isNull();
    }

    @Test
    void legacyPriceOnlyRowWaitsForNewVersionWithoutFakeMetadata() {
        jdbc.update("INSERT INTO product_price_cache(product_id,unit_price,source_version,updated_at) VALUES(4,440,5,NOW())");
        cartCommand.addItem(42L, new AddCartItemCommand(4L, 1));
        apply(4L, 555L, 5L, "equal", "ACTIVE");
        var legacy = cartQuery.getCart(42L).items().get(0);
        assertThat(legacy.unitPrice()).isEqualTo(440L);
        assertThat(legacy.productName()).isNull();
        assertThat(legacy.available()).isNull();
        apply(4L, 660L, 6L, "new", "ACTIVE");
        assertThat(cartQuery.getCart(42L).items().get(0).productName()).isEqualTo("new");
    }

    private void apply(Long id, long price, long version, String name, String status) {
        transactions.executeWithoutResult(ignored -> repository.applyUpdate(id, price, version, name, status));
    }

    private ProductPriceCache row(Long id) {
        return repository.findAllByProductIdIn(List.of(id)).get(0);
    }

    private static void await(java.util.concurrent.CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
