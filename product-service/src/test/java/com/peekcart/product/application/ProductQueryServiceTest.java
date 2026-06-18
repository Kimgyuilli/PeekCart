package com.peekcart.product.application;

import com.peekcart.global.cache.CachedPage;
import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.application.dto.ProductInfoDto;
import com.peekcart.product.application.dto.ProductListDto;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.ProductFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ServiceTest
@DisplayName("ProductQueryService 단위 테스트")
class ProductQueryServiceTest {

    @InjectMocks ProductQueryService productQueryService;

    @Mock ProductCacheService productCacheService;
    @Mock InventoryRepository inventoryRepository;

    private final Pageable pageable = PageRequest.of(0, 10);

    // ── getProducts ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getProducts: categoryId가 null이면 전체 ON_SALE 상품을 조회한다")
    void getProducts_withoutCategory_callsCacheService() {
        ProductListDto listDto = ProductFixture.productListDto();
        given(productCacheService.getProductList(null, pageable))
                .willReturn(new CachedPage<>(List.of(listDto), 1, 0, 10));

        Page<ProductListDto> result = productQueryService.getProducts(null, pageable);

        assertThat(result).hasSize(1);
        then(productCacheService).should().getProductList(null, pageable);
    }

    @Test
    @DisplayName("getProducts: categoryId가 있으면 카테고리 필터로 조회한다")
    void getProducts_withCategory_callsCacheService() {
        ProductListDto listDto = ProductFixture.productListDto();
        given(productCacheService.getProductList(ProductFixture.DEFAULT_CATEGORY_ID, pageable))
                .willReturn(new CachedPage<>(List.of(listDto), 1, 0, 10));

        Page<ProductListDto> result = productQueryService.getProducts(ProductFixture.DEFAULT_CATEGORY_ID, pageable);

        assertThat(result).hasSize(1);
        then(productCacheService).should().getProductList(ProductFixture.DEFAULT_CATEGORY_ID, pageable);
    }

    // ── getProduct ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("getProduct: 캐시된 상품 정보와 실시간 재고를 조합하여 DTO를 반환한다")
    void getProduct_success_returnsDto() {
        ProductInfoDto infoDto = ProductFixture.productInfoDto();
        given(productCacheService.getProductInfo(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(infoDto);
        given(inventoryRepository.findByProductId(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(Optional.of(ProductFixture.inventoryWithId(ProductFixture.productWithId(ProductFixture.categoryWithId()))));

        ProductDetailDto result = productQueryService.getProduct(ProductFixture.DEFAULT_PRODUCT_ID);

        assertThat(result.id()).isEqualTo(ProductFixture.DEFAULT_PRODUCT_ID);
        assertThat(result.name()).isEqualTo(ProductFixture.DEFAULT_PRODUCT_NAME);
        assertThat(result.stock()).isEqualTo(ProductFixture.DEFAULT_STOCK);
    }

    @Test
    @DisplayName("getProduct: 재고 정보가 없으면 stock=0을 반환한다")
    void getProduct_noInventory_returnsZeroStock() {
        ProductInfoDto infoDto = ProductFixture.productInfoDto();
        given(productCacheService.getProductInfo(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(infoDto);
        given(inventoryRepository.findByProductId(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(Optional.empty());

        ProductDetailDto result = productQueryService.getProduct(ProductFixture.DEFAULT_PRODUCT_ID);

        assertThat(result.stock()).isZero();
    }
}
