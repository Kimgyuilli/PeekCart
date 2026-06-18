package com.peekcart.order.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.config.SecurityConfig;
import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.CartCommandService;
import com.peekcart.order.application.CartQueryService;
import com.peekcart.order.application.dto.CartDetailDto;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.presentation.dto.request.AddCartItemRequest;
import com.peekcart.order.presentation.dto.request.UpdateCartItemRequest;
import com.peekcart.support.WithMockLoginUser;
import com.peekcart.support.fixture.OrderFixture;
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
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = CartController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = SecurityConfig.class))
@Import(TestSecurityConfig.class)
@DisplayName("CartController 슬라이스 테스트")
class CartControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean CartCommandService cartCommandService;
    @MockitoBean CartQueryService cartQueryService;

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/cart: 장바구니를 반환한다")
    void getCart_success() throws Exception {
        CartDetailDto dto = OrderFixture.cartDetailDto();
        given(cartQueryService.getCart(1L)).willReturn(dto);

        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(OrderFixture.DEFAULT_CART_ID))
                .andExpect(jsonPath("$.data.items[0].productId").value(OrderFixture.DEFAULT_PRODUCT_ID));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/cart: 장바구니가 없으면 빈 DTO를 반환한다")
    void getCart_empty_returnsEmptyDto() throws Exception {
        given(cartQueryService.getCart(1L)).willReturn(CartDetailDto.empty());

        mockMvc.perform(get("/api/v1/cart"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /api/v1/cart/items: 상품 추가에 성공하면 201을 반환한다")
    void addItem_success_returns201() throws Exception {
        CartDetailDto dto = OrderFixture.cartDetailDto();
        given(cartCommandService.addItem(eq(1L), any())).willReturn(dto);

        AddCartItemRequest request = new AddCartItemRequest(OrderFixture.DEFAULT_PRODUCT_ID, OrderFixture.DEFAULT_QUANTITY);

        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /api/v1/cart/items: productId가 null이면 400을 반환한다")
    void addItem_nullProductId_returns400() throws Exception {
        AddCartItemRequest request = new AddCartItemRequest(null, 1);

        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /api/v1/cart/items: 상품이 로컬 캐시에 없으면 409 ORD-009를 반환한다")
    void addItem_productNotInCache_returns409() throws Exception {
        willThrow(new OrderException(ErrorCode.ORD_009))
                .given(cartCommandService).addItem(eq(1L), any());

        AddCartItemRequest request = new AddCartItemRequest(OrderFixture.DEFAULT_PRODUCT_ID, OrderFixture.DEFAULT_QUANTITY);

        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("ORD-009"))
                .andExpect(jsonPath("$.message").value(ErrorCode.ORD_009.getMessage()));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /api/v1/cart/items: 수량이 0이면 400을 반환한다")
    void addItem_zeroQuantity_returns400() throws Exception {
        AddCartItemRequest request = new AddCartItemRequest(1L, 0);

        mockMvc.perform(post("/api/v1/cart/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("PUT /api/v1/cart/items/{itemId}: 수량 변경에 성공하면 200을 반환한다")
    void updateItem_success_returns200() throws Exception {
        CartDetailDto dto = OrderFixture.cartDetailDto();
        given(cartCommandService.updateItem(eq(1L), eq(OrderFixture.DEFAULT_CART_ITEM_ID), any())).willReturn(dto);

        UpdateCartItemRequest request = new UpdateCartItemRequest(5);

        mockMvc.perform(put("/api/v1/cart/items/{itemId}", OrderFixture.DEFAULT_CART_ITEM_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("DELETE /api/v1/cart/items/{itemId}: 항목 삭제에 성공하면 204를 반환한다")
    void removeItem_success_returns204() throws Exception {
        willDoNothing().given(cartCommandService).removeItem(1L, OrderFixture.DEFAULT_CART_ITEM_ID);

        mockMvc.perform(delete("/api/v1/cart/items/{itemId}", OrderFixture.DEFAULT_CART_ITEM_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("DELETE /api/v1/cart/items/{itemId}: 장바구니가 없으면 404를 반환한다")
    void removeItem_noCart_returns404() throws Exception {
        willThrow(new OrderException(ErrorCode.ORD_006))
                .given(cartCommandService).removeItem(1L, 1L);

        mockMvc.perform(delete("/api/v1/cart/items/{itemId}", 1L))
                .andExpect(status().isNotFound());
    }
}
