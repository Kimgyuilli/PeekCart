package com.peekcart.order.presentation;

import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.order.application.CartCommandService;
import com.peekcart.order.application.CartQueryService;
import com.peekcart.order.application.dto.CartDetailDto;
import com.peekcart.order.application.dto.CartItemDto;
import com.peekcart.order.infrastructure.security.OrderSecurityConfig;
import com.peekcart.support.WithMockLoginUser;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = CartController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = OrderSecurityConfig.class))
@Import(TestSecurityConfig.class)
class CartProductResponseContractTest {
    @Autowired MockMvc mvc;
    @MockitoBean CartCommandService command;
    @MockitoBean CartQueryService query;

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT"})
    @WithMockLoginUser
    void actualHttpResponsesCarryProductMetadata(String method) throws Exception {
        stub(new CartItemDto(9L, 5L, 2, "상품 이름", 1234L, "INACTIVE", false));
        perform(method)
                .andExpect(jsonPath("$.data.items[0].id").value(9))
                .andExpect(jsonPath("$.data.items[0].productId").value(5))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].productName").value("상품 이름"))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(1234))
                .andExpect(jsonPath("$.data.items[0].productStatus").value("INACTIVE"))
                .andExpect(jsonPath("$.data.items[0].available").value(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "PUT"})
    @WithMockLoginUser
    void missingProductDoesNotRemoveItemAndMetadataIsExplicitNull(String method) throws Exception {
        stub(new CartItemDto(9L, 5L, 2, null, null, null, null));
        perform(method)
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].productId").value(5))
                .andExpect(jsonPath("$.data.items[0]", org.hamcrest.Matchers.hasKey("productName")))
                .andExpect(jsonPath("$.data.items[0]", org.hamcrest.Matchers.hasKey("unitPrice")))
                .andExpect(jsonPath("$.data.items[0]", org.hamcrest.Matchers.hasKey("productStatus")))
                .andExpect(jsonPath("$.data.items[0]", org.hamcrest.Matchers.hasKey("available")))
                .andExpect(jsonPath("$.data.items[0].productName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].unitPrice").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].productStatus").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.items[0].available").value(org.hamcrest.Matchers.nullValue()));
    }

    private void stub(CartItemDto item) {
        var cart = new CartDetailDto(1L, List.of(item));
        given(query.getCart(1L)).willReturn(cart);
        given(command.addItem(eq(1L), any())).willReturn(cart);
        given(command.updateItem(eq(1L), eq(9L), any())).willReturn(cart);
    }

    private ResultActions perform(String method) throws Exception {
        return switch (method) {
            case "GET" -> mvc.perform(get("/api/v1/cart")).andExpect(status().isOk());
            case "POST" -> mvc.perform(post("/api/v1/cart/items").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"productId\":5,\"quantity\":2}")).andExpect(status().isCreated());
            default -> mvc.perform(put("/api/v1/cart/items/9").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"quantity\":2}")).andExpect(status().isOk());
        };
    }
}
