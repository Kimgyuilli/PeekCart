package com.peekcart.product.application;

import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.domain.repository.ProductRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.ProductFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * {@link ProductCacheService#getStock} 단위 테스트.
 *
 * <p>재고 조회의 "행이 없으면 0" 규칙은 원래 {@code ProductQueryService} 에 있었고
 * D-026 에서 이 클래스로 옮겨왔다(ADR-0026 D2). 옮겨온 규칙의 커버리지도 함께 옮긴다.
 *
 * <p>여기서는 {@code @Cacheable} 이 돌지 않는다(프록시 없는 단위 테스트) — 캐시 동작은
 * {@code ProductDetailQueryCostIntegrationTest} · {@code ProductStockCacheStalenessIntegrationTest}
 * 가 본다. 이 테스트가 보는 것은 <b>캐시 미스 시 실행되는 본문</b>이다.
 */
@ServiceTest
@DisplayName("ProductCacheService 단위 테스트")
class ProductCacheServiceTest {

    @InjectMocks ProductCacheService productCacheService;

    @Mock ProductRepository productRepository;
    @Mock InventoryRepository inventoryRepository;

    @Test
    @DisplayName("getStock: 재고가 있으면 그 수량을 반환한다")
    void getStock_returnsStock() {
        given(inventoryRepository.findByProductId(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(Optional.of(ProductFixture.inventoryWithId(
                        ProductFixture.productWithId(ProductFixture.categoryWithId()))));

        assertThat(productCacheService.getStock(ProductFixture.DEFAULT_PRODUCT_ID))
                .isEqualTo(ProductFixture.DEFAULT_STOCK);
    }

    @Test
    @DisplayName("getStock: 재고 행이 없으면 0을 반환한다 (상세 조회는 재고 부재로 실패하지 않는다)")
    void getStock_noInventory_returnsZero() {
        given(inventoryRepository.findByProductId(99L)).willReturn(Optional.empty());

        assertThat(productCacheService.getStock(99L)).isZero();
    }
}
