package com.peekcart.order.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.order.infrastructure.security.OrderSecurityConfig;
import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.application.OrderQueryService;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.application.dto.OrderSummaryDto;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.presentation.dto.request.CreateOrderRequest;
import com.peekcart.support.WithMockLoginUser;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(
        controllers = OrderController.class,
        excludeFilters = @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                classes = OrderSecurityConfig.class))
@Import(TestSecurityConfig.class)
@DisplayName("OrderController 슬라이스 테스트")
class OrderControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean OrderCommandService orderCommandService;
    @MockitoBean OrderQueryService orderQueryService;

    @Test
    @WithMockLoginUser
    @DisplayName("POST /api/v1/orders: 주문 생성에 성공하면 201을 반환한다")
    void createOrder_success_returns201() throws Exception {
        OrderDetailDto dto = OrderFixture.orderDetailDto();
        given(orderCommandService.createOrder(eq(1L), any())).willReturn(dto);

        CreateOrderRequest request = new CreateOrderRequest(
                OrderFixture.DEFAULT_RECEIVER_NAME, OrderFixture.DEFAULT_PHONE,
                OrderFixture.DEFAULT_ZIPCODE, OrderFixture.DEFAULT_ADDRESS);

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.orderNumber").value(OrderFixture.DEFAULT_ORDER_NUMBER))
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /api/v1/orders: receiverName이 빈 값이면 400을 반환한다")
    void createOrder_blankReceiverName_returns400() throws Exception {
        CreateOrderRequest request = new CreateOrderRequest("", "010-1234-5678", "12345", "서울");

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders: 주문 목록을 반환한다")
    void getOrders_success() throws Exception {
        OrderSummaryDto dto = OrderFixture.orderSummaryDto();
        given(orderQueryService.getOrders(eq(1L), any())).willReturn(new PageImpl<>(List.of(dto)));

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].orderNumber").value(OrderFixture.DEFAULT_ORDER_NUMBER));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders/{id}: 주문 상세를 반환한다")
    void getOrder_success() throws Exception {
        OrderDetailDto dto = OrderFixture.orderDetailDto();
        given(orderQueryService.getOrder(1L, OrderFixture.DEFAULT_ORDER_ID)).willReturn(dto);

        mockMvc.perform(get("/api/v1/orders/{id}", OrderFixture.DEFAULT_ORDER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderNumber").value(OrderFixture.DEFAULT_ORDER_NUMBER))
                .andExpect(jsonPath("$.data.items[0].productId").value(OrderFixture.DEFAULT_PRODUCT_ID));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders/{id}: 주문이 없으면 404를 반환한다")
    void getOrder_notFound_returns404() throws Exception {
        given(orderQueryService.getOrder(1L, 99L))
                .willThrow(new OrderException(ErrorCode.ORD_001));

        mockMvc.perform(get("/api/v1/orders/{id}", 99L))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /api/v1/orders/{id}/cancel: 주문 취소에 성공하면 204를 반환한다")
    void cancelOrder_success_returns204() throws Exception {
        willDoNothing().given(orderCommandService).cancelOrder(1L, OrderFixture.DEFAULT_ORDER_ID);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", OrderFixture.DEFAULT_ORDER_ID))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("POST /api/v1/orders/{id}/cancel: 이미 취소된 주문이면 400을 반환한다")
    void cancelOrder_alreadyCancelled_returns400() throws Exception {
        willThrow(new OrderException(ErrorCode.ORD_002))
                .given(orderCommandService).cancelOrder(1L, 1L);

        mockMvc.perform(post("/api/v1/orders/{id}/cancel", 1L))
                .andExpect(status().isBadRequest());
    }
}
