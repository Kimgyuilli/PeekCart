package com.peekcart.product.application;

import com.peekcart.global.cache.CachedPage;
import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.application.dto.ProductInfoDto;
import com.peekcart.product.application.dto.ProductListDto;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ServiceTest
@DisplayName("ProductQueryService 단위 테스트")
class ProductQueryServiceTest {

    @InjectMocks ProductQueryService productQueryService;

    @Mock ProductCacheService productCacheService;

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
    @DisplayName("getProduct: 상품 정보 캐시와 재고 캐시를 조합하여 DTO를 반환한다 (ADR-0026 D2)")
    void getProduct_success_returnsDto() {
        ProductInfoDto infoDto = ProductFixture.productInfoDto();
        given(productCacheService.getProductInfo(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(infoDto);
        given(productCacheService.getStock(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(ProductFixture.DEFAULT_STOCK);

        ProductDetailDto result = productQueryService.getProduct(ProductFixture.DEFAULT_PRODUCT_ID);

        assertThat(result.id()).isEqualTo(ProductFixture.DEFAULT_PRODUCT_ID);
        assertThat(result.name()).isEqualTo(ProductFixture.DEFAULT_PRODUCT_NAME);
        assertThat(result.stock()).isEqualTo(ProductFixture.DEFAULT_STOCK);
        // 재고를 리포지터리가 아니라 캐시 서비스에서 받는다는 것이 이 테스트의 계약이다 —
        // 여기로 되돌아가면 상세의 DB 왕복이 되살아난다(D-026).
        then(productCacheService).should().getStock(ProductFixture.DEFAULT_PRODUCT_ID);
    }
}
