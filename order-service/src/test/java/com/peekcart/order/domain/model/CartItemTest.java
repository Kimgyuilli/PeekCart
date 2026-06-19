package com.peekcart.order.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CartItem 도메인 단위 테스트")
class CartItemTest {

    @Test
    @DisplayName("create: 정상 생성된다")
    void create_success() {
        Cart cart = OrderFixture.cart();
        CartItem item = CartItem.create(cart, 10L, 3);

        assertThat(item.getProductId()).isEqualTo(10L);
        assertThat(item.getQuantity()).isEqualTo(3);
    }

    @Test
    @DisplayName("create: 수량이 0이면 ORD-005 예외가 발생한다")
    void create_zeroQuantity_throwsORD005() {
        Cart cart = OrderFixture.cart();

        assertThatThrownBy(() -> CartItem.create(cart, 10L, 0))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_005);
    }

    @Test
    @DisplayName("changeQuantity: 수량이 변경된다")
    void changeQuantity_success() {
        Cart cart = OrderFixture.cart();
        CartItem item = CartItem.create(cart, 10L, 1);

        item.changeQuantity(5);

        assertThat(item.getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("changeQuantity: 수량이 0이면 ORD-005 예외가 발생한다")
    void changeQuantity_zero_throwsORD005() {
        Cart cart = OrderFixture.cart();
        CartItem item = CartItem.create(cart, 10L, 1);

        assertThatThrownBy(() -> item.changeQuantity(0))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_005);
    }

    @Test
    @DisplayName("addQuantity: 수량이 합산된다")
    void addQuantity_success() {
        Cart cart = OrderFixture.cart();
        cart.addItem(10L, 2);
        // addQuantity는 package-private이므로 Cart.addItem 경유로 테스트
        cart.addItem(10L, 3);

        assertThat(cart.getItems().get(0).getQuantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("addQuantity: delta가 0이면 ORD-005 예외가 발생한다")
    void addQuantity_zeroDelta_throwsORD005() {
        Cart cart = OrderFixture.cart();
        cart.addItem(10L, 2);

        assertThatThrownBy(() -> cart.addItem(10L, 0))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_005);
    }

    @Test
    @DisplayName("addQuantity: delta가 음수면 ORD-005 예외가 발생한다")
    void addQuantity_negativeDelta_throwsORD005() {
        Cart cart = OrderFixture.cart();
        cart.addItem(10L, 2);

        assertThatThrownBy(() -> cart.addItem(10L, -1))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_005);
    }
}
