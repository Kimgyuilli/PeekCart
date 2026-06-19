package com.peekcart.order.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.dto.CreateOrderCommand;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Cart;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.CartRepository;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import com.peekcart.order.infrastructure.outbox.OrderOutboxEventPublisher;
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
import static org.mockito.Mockito.never;

@ServiceTest
@DisplayName("OrderCommandService 단위 테스트")
class OrderCommandServiceTest {

    @InjectMocks OrderCommandService orderCommandService;
    @Mock OrderRepository orderRepository;
    @Mock CartRepository cartRepository;
    @Mock ProductPriceCacheRepository priceCacheRepository;
    @Mock OrderOutboxEventPublisher outboxEventPublisher;

    @Test
    @DisplayName("createOrder: 장바구니 기반 주문이 생성되고 이벤트가 발행된다")
    void createOrder_success() {
        Cart cart = OrderFixture.cartWithItem();
        CreateOrderCommand command = OrderFixture.createOrderCommand();
        given(cartRepository.findByUserId(OrderFixture.DEFAULT_USER_ID)).willReturn(Optional.of(cart));
        given(priceCacheRepository.findUnitPrice(OrderFixture.DEFAULT_PRODUCT_ID))
                .willReturn(Optional.of(OrderFixture.DEFAULT_UNIT_PRICE));
        given(orderRepository.save(any(Order.class))).willAnswer(invocation -> invocation.getArgument(0));

        OrderDetailDto result = orderCommandService.createOrder(OrderFixture.DEFAULT_USER_ID, command);

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.totalAmount()).isEqualTo(OrderFixture.DEFAULT_UNIT_PRICE * OrderFixture.DEFAULT_QUANTITY);
        assertThat(result.receiverName()).isEqualTo(OrderFixture.DEFAULT_RECEIVER_NAME);
        assertThat(result.items()).hasSize(1);
        assertThat(cart.isEmpty()).isTrue();
        // 단가는 Product 동기 호출 없이 로컬 가격 캐시에서 읽는다 (CQRS ⑤, strangler-2)
        then(priceCacheRepository).should().findUnitPrice(OrderFixture.DEFAULT_PRODUCT_ID);
        then(outboxEventPublisher).should().publishOrderCreated(any(Order.class));
    }

    @Test
    @DisplayName("createOrder: 가격 캐시 미스이면 ORD-007 예외가 발생한다")
    void createOrder_priceCacheMiss_throwsORD007() {
        Cart cart = OrderFixture.cartWithItem();
        given(cartRepository.findByUserId(OrderFixture.DEFAULT_USER_ID)).willReturn(Optional.of(cart));
        given(priceCacheRepository.findUnitPrice(OrderFixture.DEFAULT_PRODUCT_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCommandService.createOrder(OrderFixture.DEFAULT_USER_ID, OrderFixture.createOrderCommand()))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_007);

        then(outboxEventPublisher).should(never()).publishOrderCreated(any(Order.class));
    }

    @Test
    @DisplayName("createOrder: 장바구니가 없으면 ORD-006 예외가 발생한다")
    void createOrder_noCart_throwsORD006() {
        given(cartRepository.findByUserId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCommandService.createOrder(1L, OrderFixture.createOrderCommand()))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_006);
    }

    @Test
    @DisplayName("createOrder: 장바구니가 비어있으면 ORD-004 예외가 발생한다")
    void createOrder_emptyCart_throwsORD004() {
        Cart emptyCart = OrderFixture.cartWithId();
        given(cartRepository.findByUserId(OrderFixture.DEFAULT_USER_ID)).willReturn(Optional.of(emptyCart));

        assertThatThrownBy(() -> orderCommandService.createOrder(OrderFixture.DEFAULT_USER_ID, OrderFixture.createOrderCommand()))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_004);
    }

    @Test
    @DisplayName("cancelOrder: 주문이 취소되고 order.cancelled 가 발행된다 (재고 복구는 비동기)")
    void cancelOrder_success() {
        Order order = OrderFixture.orderWithId();
        given(orderRepository.findByIdAndUserId(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID))
                .willReturn(Optional.of(order));

        orderCommandService.cancelOrder(OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        // 재고 복구는 동기로 하지 않는다 — order.cancelled → Product release Saga (ADR-0012 D3)
        then(outboxEventPublisher).should().publishOrderCancelled(any(Order.class));
    }

    @Test
    @DisplayName("cancelOrder: 주문이 없으면 ORD-001 예외가 발생한다")
    void cancelOrder_notFound_throwsORD001() {
        given(orderRepository.findByIdAndUserId(99L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCommandService.cancelOrder(1L, 99L))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_001);

        then(outboxEventPublisher).should(never()).publishOrderCancelled(any(Order.class));
    }

    @Test
    @DisplayName("cancelExpiredOrder: 타임아웃 주문이 취소되고 order.cancelled 가 발행된다 (재고 복구는 비동기)")
    void cancelExpiredOrder_success() {
        Order order = OrderFixture.paymentRequestedOrderWithId();
        given(orderRepository.findById(OrderFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(order));

        orderCommandService.cancelExpiredOrder(OrderFixture.DEFAULT_ORDER_ID);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        then(outboxEventPublisher).should().publishOrderCancelled(any(Order.class));
    }

    @Test
    @DisplayName("cancelExpiredOrder: 주문이 없으면 ORD-001 예외가 발생한다")
    void cancelExpiredOrder_notFound_throwsORD001() {
        given(orderRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderCommandService.cancelExpiredOrder(99L))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_001);

        then(outboxEventPublisher).should(never()).publishOrderCancelled(any(Order.class));
    }
}
