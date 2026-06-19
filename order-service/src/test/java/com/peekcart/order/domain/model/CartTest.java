package com.peekcart.order.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Cart 도메인 단위 테스트")
class CartTest {

    @Test
    @DisplayName("create: 빈 장바구니가 생성된다")
    void create_emptyCart() {
        Cart cart = Cart.create(1L);
        assertThat(cart.isEmpty()).isTrue();
        assertThat(cart.getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("addItem: 새 상품이 추가된다")
    void addItem_newProduct_added() {
        Cart cart = OrderFixture.cart();
        cart.addItem(10L, 2);

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getProductId()).isEqualTo(10L);
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    @DisplayName("addItem: 동일 상품이면 수량을 합산한다")
    void addItem_existingProduct_mergesQuantity() {
        Cart cart = OrderFixture.cart();
        cart.addItem(10L, 2);
        cart.addItem(10L, 3);

        assertThat(cart.getItems()).hasSize(1);
        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("addItem: 다른 상품이면 별도 항목으로 추가된다")
    void addItem_differentProduct_separateItems() {
        Cart cart = OrderFixture.cart();
        cart.addItem(10L, 2);
        cart.addItem(20L, 1);

        assertThat(cart.getItems()).hasSize(2);
    }

    @Test
    @DisplayName("updateItemQuantity: 수량이 변경된다")
    void updateItemQuantity_success() {
        Cart cart = OrderFixture.cartWithItem();
        Long cartItemId = cart.getItems().get(0).getId();

        cart.updateItemQuantity(cartItemId, 5);

        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("updateItemQuantity: 존재하지 않는 항목이면 ORD-006 예외가 발생한다")
    void updateItemQuantity_notFound_throwsORD006() {
        Cart cart = OrderFixture.cartWithItem();

        assertThatThrownBy(() -> cart.updateItemQuantity(999L, 5))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_006);
    }

    @Test
    @DisplayName("removeItem: 항목이 제거된다")
    void removeItem_success() {
        Cart cart = OrderFixture.cartWithItem();
        Long cartItemId = cart.getItems().get(0).getId();

        cart.removeItem(cartItemId);

        assertThat(cart.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("removeItem: 존재하지 않는 항목이면 ORD-006 예외가 발생한다")
    void removeItem_notFound_throwsORD006() {
        Cart cart = OrderFixture.cartWithItem();

        assertThatThrownBy(() -> cart.removeItem(999L))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_006);
    }

    @Test
    @DisplayName("clear: 모든 항목이 제거된다")
    void clear_removesAllItems() {
        Cart cart = OrderFixture.cartWithItem();
        cart.clear();
        assertThat(cart.isEmpty()).isTrue();
    }
}
