package com.peekcart.order.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.order.infrastructure.security.OrderSecurityConfig;
import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.application.OrderQueryService;
import com.peekcart.order.application.dto.CursorSlice;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.application.dto.OrderSummaryDto;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.OrderCursor;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.presentation.dto.request.CreateOrderRequest;
import com.peekcart.support.WithMockLoginUser;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    private static String cursorOf(String raw) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders: 커서 페이지를 반환한다 — totalElements 는 없다")
    void getOrders_success() throws Exception {
        OrderSummaryDto dto = OrderFixture.orderSummaryDto();
        String next = new OrderCursor(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 0), 7L).encode();
        given(orderQueryService.getOrders(eq(1L), any(), anyInt()))
                .willReturn(new CursorSlice<>(List.of(dto), next, true));

        mockMvc.perform(get("/api/v1/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].orderNumber").value(OrderFixture.DEFAULT_ORDER_NUMBER))
                .andExpect(jsonPath("$.data.nextCursor").value(next))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.totalElements").doesNotExist())
                .andExpect(jsonPath("$.data.totalPages").doesNotExist())
                .andExpect(jsonPath("$.data.number").doesNotExist());
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders: cursor 없으면 null, size 없으면 20 으로 위임한다")
    void getOrders_defaults() throws Exception {
        given(orderQueryService.getOrders(eq(1L), any(), anyInt()))
                .willReturn(new CursorSlice<>(List.of(), null, false));

        mockMvc.perform(get("/api/v1/orders")).andExpect(status().isOk());

        verify(orderQueryService).getOrders(1L, null, 20);
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders: 유효한 커서를 디코드해 위임한다")
    void getOrders_decodesCursor() throws Exception {
        given(orderQueryService.getOrders(eq(1L), any(), anyInt()))
                .willReturn(new CursorSlice<>(List.of(), null, false));

        mockMvc.perform(get("/api/v1/orders")
                        .param("cursor", cursorOf("2026-01-02T03:04:05.123456|9"))
                        .param("size", "5"))
                .andExpect(status().isOk());

        verify(orderQueryService).getOrders(
                1L, new OrderCursor(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_456_000), 9L), 5);
    }

    @ParameterizedTest(name = "[{index}] cursor={0}")
    @WithMockLoginUser
    @ValueSource(strings = {
            "!!!not-base64!!!",
            "YWJjfDE",                                              // "abc|1"
            "MjAyNi0wMS0wMlQwMzowNDowNS4xMjM0NTY",                  // 구분자 부재
            "MjAyNi0wMS0wMlQwMzowNDowNS4xMjM0NTZ8MXwy",             // 구분자 2개
            "MjAyNi0wMS0wMlQwMzowNDowNS4xMjM0NTZ8LTE",              // id 음수
            "MjAyNi0wMS0wMlQwMzowNDowNS4xMjM0NTY3fDE",              // 나노 7자리
            "MjAyNi0wMi0zMFQwMzowNDowNS4xMjM0NTZ8MQ",               // 2026-02-30 (존재하지 않는 날짜)
            "KzEwMDAwLTAxLTAyVDAzOjA0OjA1LjEyMzQ1Nnwx",             // +10000 연도
            "LTAwMDEtMDEtMDJUMDM6MDQ6MDUuMTIzNDU2fDE",              // -0001 연도
    })
    @DisplayName("GET /api/v1/orders: 잘못된 커서는 400 ORD-010, 서비스는 호출되지 않는다")
    void getOrders_invalidCursor(String cursor) throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("cursor", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORD-010"));

        verifyNoInteractions(orderQueryService);
    }

    @ParameterizedTest(name = "[{index}] size={0}")
    @WithMockLoginUser
    @ValueSource(strings = {"0", "101", "-1", "abc", "", "2147483648", "1.5"})
    @DisplayName("GET /api/v1/orders: 잘못된 size 는 400 ORD-011, 서비스는 호출되지 않는다")
    void getOrders_invalidSize(String size) throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("size", size))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORD-011"));

        verifyNoInteractions(orderQueryService);
    }

    @ParameterizedTest(name = "[{index}] {0}={1}")
    @WithMockLoginUser
    @CsvSource({"page,1", "sort,'id,asc'", "offset,5"})
    @DisplayName("GET /api/v1/orders: 폐기된 offset 파라미터는 400 ORD-012 — 조용히 무시하지 않는다")
    void getOrders_removedParams(String name, String value) throws Exception {
        mockMvc.perform(get("/api/v1/orders").param(name, value))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORD-012"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString(name)));

        verifyNoInteractions(orderQueryService);
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders: 폐기 파라미터를 여러 개 보내면 전부 메시지에 담긴다")
    void getOrders_multipleRemovedParams() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("page", "1").param("sort", "id,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORD-012"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("page"),
                        org.hamcrest.Matchers.containsString("sort"))));

        verifyNoInteractions(orderQueryService);
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders: 중복 파라미터는 첫 값만 보고 통과시키지 않는다")
    void getOrders_duplicateParams() throws Exception {
        mockMvc.perform(get("/api/v1/orders").param("size", "20").param("size", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORD-011"));

        mockMvc.perform(get("/api/v1/orders")
                        .param("cursor", cursorOf("2026-01-02T03:04:05.123456|9"))
                        .param("cursor", "쓰레기"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ORD-010"));

        verifyNoInteractions(orderQueryService);
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders: 오류 우선순위는 폐기 > 커서 > size 다")
    void getOrders_errorPriority() throws Exception {
        // 폐기 파라미터가 다른 오류보다 먼저 잡힌다 — 구 클라이언트에게 원인을 정확히 알려야 한다.
        mockMvc.perform(get("/api/v1/orders").param("page", "1").param("size", "abc"))
                .andExpect(jsonPath("$.code").value("ORD-012"));
        mockMvc.perform(get("/api/v1/orders").param("page", "1").param("cursor", "쓰레기"))
                .andExpect(jsonPath("$.code").value("ORD-012"));
        // 폐기가 없으면 커서가 size 보다 먼저다.
        mockMvc.perform(get("/api/v1/orders").param("cursor", "쓰레기").param("size", "abc"))
                .andExpect(jsonPath("$.code").value("ORD-010"));

        verifyNoInteractions(orderQueryService);
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/orders: 무관한 파라미터는 거부하지 않는다 (denylist)")
    void getOrders_unrelatedParamsAllowed() throws Exception {
        given(orderQueryService.getOrders(eq(1L), any(), anyInt()))
                .willReturn(new CursorSlice<>(List.of(), null, false));

        mockMvc.perform(get("/api/v1/orders").param("_", "123").param("utm_source", "x"))
                .andExpect(status().isOk());
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
    @Test
    @WithMockLoginUser
    void invalidPathIdReturns400() throws Exception {
        mockMvc.perform(get("/api/v1/orders/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS-003"));
    }
}
