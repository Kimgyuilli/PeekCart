package com.peekcart.order.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.dto.AddCartItemCommand;
import com.peekcart.order.application.dto.CartDetailDto;
import com.peekcart.order.application.dto.UpdateCartItemCommand;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Cart;
import com.peekcart.order.domain.repository.CartRepository;
import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ServiceTest
@DisplayName("CartCommandService 단위 테스트")
class CartCommandServiceTest {

    @InjectMocks CartCommandService cartCommandService;
    @Mock CartRepository cartRepository;
    @Mock ProductPriceCacheRepository priceCacheRepository;

    @Test
    @DisplayName("addItem: 캐시에 존재하는 상품이면 기존 장바구니에 추가된다")
    void addItem_existingCart_success() {
        Cart cart = OrderFixture.cartWithId();
        AddCartItemCommand command = OrderFixture.addCartItemCommand();
        given(priceCacheRepository.existsByProductId(OrderFixture.DEFAULT_PRODUCT_ID)).willReturn(true);
        given(cartRepository.findByUserId(OrderFixture.DEFAULT_USER_ID)).willReturn(Optional.of(cart));

        CartDetailDto result = cartCommandService.addItem(OrderFixture.DEFAULT_USER_ID, command);

        assertThat(result.items()).hasSize(1);
        then(priceCacheRepository).should().existsByProductId(OrderFixture.DEFAULT_PRODUCT_ID);
    }

    @Test
    @DisplayName("addItem: 장바구니가 없으면 새로 생성한다")
    void addItem_noCart_createsNew() {
        Cart newCart = OrderFixture.cartWithId();
        AddCartItemCommand command = OrderFixture.addCartItemCommand();
        given(priceCacheRepository.existsByProductId(OrderFixture.DEFAULT_PRODUCT_ID)).willReturn(true);
        given(cartRepository.findByUserId(OrderFixture.DEFAULT_USER_ID)).willReturn(Optional.empty());
        given(cartRepository.save(any(Cart.class))).willReturn(newCart);

        CartDetailDto result = cartCommandService.addItem(OrderFixture.DEFAULT_USER_ID, command);

        assertThat(result.id()).isEqualTo(OrderFixture.DEFAULT_CART_ID);
        then(cartRepository).should().save(any(Cart.class));
    }

    @Test
    @DisplayName("addItem: 상품이 로컬 캐시에 없으면 ORD-009 예외가 발생한다")
    void addItem_productNotInCache_throwsORD009() {
        AddCartItemCommand command = OrderFixture.addCartItemCommand();
        given(priceCacheRepository.existsByProductId(OrderFixture.DEFAULT_PRODUCT_ID)).willReturn(false);

        assertThatThrownBy(() -> cartCommandService.addItem(OrderFixture.DEFAULT_USER_ID, command))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_009);
        then(cartRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("updateItem: 수량이 변경된다")
    void updateItem_success() {
        Cart cart = OrderFixture.cartWithItem();
        Long cartItemId = cart.getItems().get(0).getId();
        given(cartRepository.findByUserId(OrderFixture.DEFAULT_USER_ID)).willReturn(Optional.of(cart));

        CartDetailDto result = cartCommandService.updateItem(
                OrderFixture.DEFAULT_USER_ID, cartItemId, OrderFixture.updateCartItemCommand(5));

        assertThat(result.items().get(0).quantity()).isEqualTo(5);
    }

    @Test
    @DisplayName("updateItem: 장바구니가 없으면 ORD-006 예외가 발생한다")
    void updateItem_noCart_throwsORD006() {
        given(cartRepository.findByUserId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cartCommandService.updateItem(1L, 1L, OrderFixture.updateCartItemCommand(5)))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_006);
    }

    @Test
    @DisplayName("removeItem: 항목이 제거된다")
    void removeItem_success() {
        Cart cart = OrderFixture.cartWithItem();
        Long cartItemId = cart.getItems().get(0).getId();
        given(cartRepository.findByUserId(OrderFixture.DEFAULT_USER_ID)).willReturn(Optional.of(cart));

        cartCommandService.removeItem(OrderFixture.DEFAULT_USER_ID, cartItemId);

        assertThat(cart.isEmpty()).isTrue();
    }

    @Test
    @DisplayName("removeItem: 장바구니가 없으면 ORD-006 예외가 발생한다")
    void removeItem_noCart_throwsORD006() {
        given(cartRepository.findByUserId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> cartCommandService.removeItem(1L, 1L))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_006);
    }
}
