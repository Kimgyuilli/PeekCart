package com.peekcart.order.application;

import com.peekcart.order.application.dto.CartDetailDto;
import com.peekcart.order.domain.model.Cart;
import com.peekcart.order.domain.repository.CartRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ServiceTest
@DisplayName("CartQueryService 단위 테스트")
class CartQueryServiceTest {

    @InjectMocks CartQueryService cartQueryService;
    @Mock CartRepository cartRepository;

    @Test
    @DisplayName("getCart: 장바구니가 있으면 CartDetailDto를 반환한다")
    void getCart_exists_returnsDto() {
        Cart cart = OrderFixture.cartWithItem();
        given(cartRepository.findByUserId(OrderFixture.DEFAULT_USER_ID)).willReturn(Optional.of(cart));

        CartDetailDto result = cartQueryService.getCart(OrderFixture.DEFAULT_USER_ID);

        assertThat(result.id()).isEqualTo(OrderFixture.DEFAULT_CART_ID);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    @DisplayName("getCart: 장바구니가 없으면 빈 DTO를 반환한다")
    void getCart_notExists_returnsEmptyDto() {
        given(cartRepository.findByUserId(1L)).willReturn(Optional.empty());

        CartDetailDto result = cartQueryService.getCart(1L);

        assertThat(result.id()).isNull();
        assertThat(result.items()).isEmpty();
    }
}
