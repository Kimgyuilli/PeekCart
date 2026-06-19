package com.peekcart.support.fixture;

import com.peekcart.order.application.dto.*;
import com.peekcart.order.domain.model.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Order 도메인 테스트 픽스처 팩토리.
 */
public class OrderFixture {

    public static final Long DEFAULT_USER_ID = 1L;
    public static final Long DEFAULT_ORDER_ID = 1L;
    public static final String DEFAULT_ORDER_NUMBER = "ORD-20260325-ABCD1234";
    public static final String DEFAULT_RECEIVER_NAME = "홍길동";
    public static final String DEFAULT_PHONE = "010-1234-5678";
    public static final String DEFAULT_ZIPCODE = "12345";
    public static final String DEFAULT_ADDRESS = "서울시 강남구";
    public static final Long DEFAULT_PRODUCT_ID = 10L;
    public static final int DEFAULT_QUANTITY = 2;
    public static final long DEFAULT_UNIT_PRICE = 50_000L;
    public static final Long DEFAULT_CART_ID = 1L;
    public static final Long DEFAULT_CART_ITEM_ID = 1L;

    private OrderFixture() {}

    // ── Domain 객체 ──

    public static List<OrderItemData> defaultItemDataList() {
        return List.of(new OrderItemData(DEFAULT_PRODUCT_ID, DEFAULT_QUANTITY, DEFAULT_UNIT_PRICE));
    }

    public static Order order() {
        return Order.create(
                DEFAULT_USER_ID, DEFAULT_ORDER_NUMBER,
                DEFAULT_RECEIVER_NAME, DEFAULT_PHONE, DEFAULT_ZIPCODE, DEFAULT_ADDRESS,
                defaultItemDataList()
        );
    }

    public static Order orderWithId() {
        Order order = order();
        ReflectionTestUtils.setField(order, "id", DEFAULT_ORDER_ID);
        return order;
    }

    public static Order paymentRequestedOrderWithId() {
        Order order = orderWithId();
        order.transitionTo(OrderStatus.PAYMENT_REQUESTED);
        return order;
    }

    public static Cart cart() {
        return Cart.create(DEFAULT_USER_ID);
    }

    public static Cart cartWithId() {
        Cart cart = cart();
        ReflectionTestUtils.setField(cart, "id", DEFAULT_CART_ID);
        return cart;
    }

    public static Cart cartWithItem() {
        Cart cart = cartWithId();
        cart.addItem(DEFAULT_PRODUCT_ID, DEFAULT_QUANTITY);
        CartItem item = cart.getItems().get(0);
        ReflectionTestUtils.setField(item, "id", DEFAULT_CART_ITEM_ID);
        return cart;
    }

    // ── Application DTO ──

    public static CreateOrderCommand createOrderCommand() {
        return new CreateOrderCommand(DEFAULT_RECEIVER_NAME, DEFAULT_PHONE, DEFAULT_ZIPCODE, DEFAULT_ADDRESS);
    }

    public static AddCartItemCommand addCartItemCommand() {
        return new AddCartItemCommand(DEFAULT_PRODUCT_ID, DEFAULT_QUANTITY);
    }

    public static UpdateCartItemCommand updateCartItemCommand(int quantity) {
        return new UpdateCartItemCommand(quantity);
    }

    public static OrderDetailDto orderDetailDto() {
        return new OrderDetailDto(
                DEFAULT_ORDER_ID, DEFAULT_ORDER_NUMBER,
                DEFAULT_UNIT_PRICE * DEFAULT_QUANTITY,
                "PENDING",
                DEFAULT_RECEIVER_NAME, DEFAULT_PHONE, DEFAULT_ZIPCODE, DEFAULT_ADDRESS,
                LocalDateTime.of(2026, 3, 25, 10, 0),
                List.of(new OrderItemDto(1L, DEFAULT_PRODUCT_ID, DEFAULT_QUANTITY, DEFAULT_UNIT_PRICE))
        );
    }

    public static OrderSummaryDto orderSummaryDto() {
        return new OrderSummaryDto(
                DEFAULT_ORDER_ID, DEFAULT_ORDER_NUMBER,
                DEFAULT_UNIT_PRICE * DEFAULT_QUANTITY,
                "PENDING",
                LocalDateTime.of(2026, 3, 25, 10, 0)
        );
    }

    public static CartDetailDto cartDetailDto() {
        return new CartDetailDto(
                DEFAULT_CART_ID,
                List.of(new CartItemDto(DEFAULT_CART_ITEM_ID, DEFAULT_PRODUCT_ID, DEFAULT_QUANTITY))
        );
    }
}
