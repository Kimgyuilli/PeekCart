package com.peekcart.product.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.product.infrastructure.security.ProductSecurityConfig;
import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.ProductCommandService;
import com.peekcart.product.application.dto.CreateProductCommand;
import com.peekcart.product.application.dto.UpdateProductCommand;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.presentation.dto.request.CreateProductRequest;
import com.peekcart.product.presentation.dto.request.UpdateProductRequest;
import com.peekcart.support.fixture.ProductFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AdminProductController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = ProductSecurityConfig.class))
@Import(TestSecurityConfig.class)
@DisplayName("AdminProductController 슬라이스 테스트")
class AdminProductControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean ProductCommandService productCommandService;

    private CreateProductRequest validCreateRequest() {
        return new CreateProductRequest(
                ProductFixture.DEFAULT_CATEGORY_ID,
                ProductFixture.DEFAULT_PRODUCT_NAME,
                ProductFixture.DEFAULT_DESCRIPTION,
                ProductFixture.DEFAULT_PRICE,
                ProductFixture.DEFAULT_IMAGE_URL,
                ProductFixture.DEFAULT_STOCK);
    }

    private UpdateProductRequest validUpdateRequest() {
        return new UpdateProductRequest(
                ProductFixture.DEFAULT_CATEGORY_ID,
                ProductFixture.DEFAULT_PRODUCT_NAME,
                ProductFixture.DEFAULT_DESCRIPTION,
                ProductFixture.DEFAULT_PRICE,
                ProductFixture.DEFAULT_IMAGE_URL);
    }

    // ── POST /admin/products ──────────────────────────────────────────────────

    @Test
    @DisplayName("POST /admin/products: 유효한 요청이면 201과 상품 상세를 반환한다")
    void createProduct_validRequest_returns201() throws Exception {
        given(productCommandService.create(any(CreateProductCommand.class)))
                .willReturn(ProductFixture.detailDto());

        mockMvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validCreateRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value(ProductFixture.DEFAULT_PRODUCT_NAME))
                .andExpect(jsonPath("$.data.stock").value(ProductFixture.DEFAULT_STOCK));
    }

    @Test
    @DisplayName("POST /admin/products: 상품명이 공백이면 400을 반환한다")
    void createProduct_blankName_returns400() throws Exception {
        CreateProductRequest request = new CreateProductRequest(
                1L, "", "설명", 1000L, null, 10);

        mockMvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /admin/products: 가격이 음수이면 400을 반환한다")
    void createProduct_negativePrice_returns400() throws Exception {
        CreateProductRequest request = new CreateProductRequest(
                1L, "상품명", "설명", -1L, null, 10);

        mockMvc.perform(post("/api/v1/admin/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    // ── PUT /admin/products/{id} ──────────────────────────────────────────────

    @Test
    @DisplayName("PUT /admin/products/{id}: 유효한 요청이면 200과 수정된 상품을 반환한다")
    void updateProduct_validRequest_returns200() throws Exception {
        given(productCommandService.update(eq(ProductFixture.DEFAULT_PRODUCT_ID), any(UpdateProductCommand.class)))
                .willReturn(ProductFixture.detailDto());

        mockMvc.perform(put("/api/v1/admin/products/{id}", ProductFixture.DEFAULT_PRODUCT_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value(ProductFixture.DEFAULT_PRODUCT_NAME));
    }

    @Test
    @DisplayName("PUT /admin/products/{id}: 상품이 없으면 404를 반환한다")
    void updateProduct_notFound_returns404() throws Exception {
        given(productCommandService.update(eq(99L), any(UpdateProductCommand.class)))
                .willThrow(new ProductException(ErrorCode.PRD_001));

        mockMvc.perform(put("/api/v1/admin/products/{id}", 99L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validUpdateRequest())))
                .andExpect(status().isNotFound());
    }

    // ── DELETE /admin/products/{id} ───────────────────────────────────────────

    @Test
    @DisplayName("DELETE /admin/products/{id}: 정상 요청이면 204를 반환한다")
    void deleteProduct_returns204() throws Exception {
        willDoNothing().given(productCommandService).delete(ProductFixture.DEFAULT_PRODUCT_ID);

        mockMvc.perform(delete("/api/v1/admin/products/{id}", ProductFixture.DEFAULT_PRODUCT_ID))
                .andExpect(status().isNoContent());
    }
}
