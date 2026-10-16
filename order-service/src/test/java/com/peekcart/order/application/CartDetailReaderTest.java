package com.peekcart.order.application;

import com.peekcart.order.domain.model.Cart;
import com.peekcart.order.domain.model.ProductPriceCache;
import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class CartDetailReaderTest {
    private final ProductPriceCacheRepository repository = mock(ProductPriceCacheRepository.class);
    private final CartDetailReader reader = new CartDetailReader(repository);

    @Test
    void missingAndLegacyMetadataKeepItemsWithoutInventingValues() {
        Cart cart = OrderFixture.cartWithItem();
        cart.addItem(999L, 2);
        var legacy = product(OrderFixture.DEFAULT_PRODUCT_ID, null, 7200L, null);
        when(repository.findAllByProductIdIn(anyCollection())).thenReturn(List.of(legacy));

        var result = reader.read(cart);

        assertThat(result.items()).hasSize(2);
        var knownPrice = result.items().get(0);
        assertThat(knownPrice.unitPrice()).isEqualTo(7200L);
        assertThat(knownPrice.productName()).isNull();
        assertThat(knownPrice.available()).isNull();
        var missing = result.items().get(1);
        assertThat(missing.productId()).isEqualTo(999L);
        assertThat(missing.quantity()).isEqualTo(2);
        assertThat(missing.unitPrice()).isNull();
        assertThat(missing.productStatus()).isNull();
        verify(repository).findAllByProductIdIn(List.of(OrderFixture.DEFAULT_PRODUCT_ID, 999L));
        verifyNoMoreInteractions(repository);
    }

    @Test
    void statusIsAReadModelHintAndUnknownStatusStaysUnknown() {
        for (String status : List.of("ACTIVE", "INACTIVE", "SOLD_OUT", "FUTURE_STATUS")) {
            Cart cart = OrderFixture.cartWithItem();
            when(repository.findAllByProductIdIn(anyCollection())).thenReturn(List.of(
                    product(OrderFixture.DEFAULT_PRODUCT_ID, "상품 이름", 1234L, status)));
            var item = reader.read(cart).items().get(0);
            assertThat(item.productName()).isEqualTo("상품 이름");
            assertThat(item.productStatus()).isEqualTo(status);
            Boolean expected = switch (status) {
                case "ACTIVE" -> Boolean.TRUE;
                case "FUTURE_STATUS" -> null;
                default -> Boolean.FALSE;
            };
            assertThat(item.available()).isEqualTo(expected);
        }
    }

    @Test
    void emptyCartDoesNotQueryProducts() {
        assertThat(reader.read(Cart.create(42L)).items()).isEmpty();
        verifyNoInteractions(repository);
    }

    private static ProductPriceCache product(Long id, String name, long price, String status) {
        ProductPriceCache product = org.springframework.beans.BeanUtils.instantiateClass(ProductPriceCache.class);
        ReflectionTestUtils.setField(product, "productId", id);
        ReflectionTestUtils.setField(product, "productName", name);
        ReflectionTestUtils.setField(product, "unitPrice", price);
        ReflectionTestUtils.setField(product, "productStatus", status);
        return product;
    }
}
