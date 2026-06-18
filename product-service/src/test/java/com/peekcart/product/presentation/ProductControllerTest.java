package com.peekcart.product.presentation;

import com.peekcart.product.infrastructure.security.ProductSecurityConfig;
import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.ProductQueryService;
import com.peekcart.product.application.dto.ProductListDto;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.support.fixture.ProductFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ProductController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ProductSecurityConfig.class))
@Import(TestSecurityConfig.class)
@DisplayName("ProductController 슬라이스 테스트")
class ProductControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean ProductQueryService productQueryService;

    // ── GET /products ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /products: 200과 상품 페이지를 반환한다")
    void getProducts_returns200WithPage() throws Exception {
        ProductListDto listDto = ProductFixture.productListDto();
        given(productQueryService.getProducts(isNull(), any()))
                .willReturn(new PageImpl<>(List.of(listDto)));

        mockMvc.perform(get("/api/v1/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].name").value(ProductFixture.DEFAULT_PRODUCT_NAME))
                .andExpect(jsonPath("$.data.content[0].price").value(ProductFixture.DEFAULT_PRICE));
    }

    @Test
    @DisplayName("GET /products?categoryId=1: categoryId 필터로 조회한다")
    void getProducts_withCategoryId_returns200() throws Exception {
        ProductListDto listDto = ProductFixture.productListDto();
        given(productQueryService.getProducts(eq(1L), any()))
                .willReturn(new PageImpl<>(List.of(listDto)));

        mockMvc.perform(get("/api/v1/products").param("categoryId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray());
    }

    // ── GET /products/{id} ────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /products/{id}: 200과 상품 상세를 반환한다")
    void getProduct_success_returns200() throws Exception {
        given(productQueryService.getProduct(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(ProductFixture.detailDto());

        mockMvc.perform(get("/api/v1/products/{id}", ProductFixture.DEFAULT_PRODUCT_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(ProductFixture.DEFAULT_PRODUCT_ID))
                .andExpect(jsonPath("$.data.name").value(ProductFixture.DEFAULT_PRODUCT_NAME))
                .andExpect(jsonPath("$.data.stock").value(ProductFixture.DEFAULT_STOCK));
    }

    @Test
    @DisplayName("GET /products/{id}: 상품이 없으면 404를 반환한다")
    void getProduct_notFound_returns404() throws Exception {
        given(productQueryService.getProduct(99L))
                .willThrow(new ProductException(ErrorCode.PRD_001));

        mockMvc.perform(get("/api/v1/products/{id}", 99L))
                .andExpect(status().isNotFound());
    }
}
