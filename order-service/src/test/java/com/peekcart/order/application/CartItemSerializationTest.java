package com.peekcart.order.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.order.application.dto.CartItemDto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CartItemSerializationTest {
    @Test
    void additiveMetadataDoesNotRemoveExistingFieldsAndUnknownIsJsonNull() throws Exception {
        var tree = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(
                new CartItemDto(1L, 2L, 3, null, null, null, null)));
        assertThat(tree.get("id").asLong()).isEqualTo(1L);
        assertThat(tree.get("productId").asLong()).isEqualTo(2L);
        assertThat(tree.get("quantity").asInt()).isEqualTo(3);
        for (String field : java.util.List.of("productName", "unitPrice", "productStatus", "available")) {
            assertThat(tree.has(field)).isTrue();
            assertThat(tree.get(field).isNull()).isTrue();
        }
    }
}
