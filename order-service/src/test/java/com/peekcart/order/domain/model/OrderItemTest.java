package com.peekcart.order.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("OrderItem 도메인 단위 테스트")
class OrderItemTest {

    @Test
    @DisplayName("getSubtotal: unitPrice × quantity를 반환한다")
    void getSubtotal_returnsCorrectValue() {
        Order order = OrderFixture.order();
        OrderItem item = order.getOrderItems().get(0);

        assertThat(item.getSubtotal())
                .isEqualTo(OrderFixture.DEFAULT_UNIT_PRICE * OrderFixture.DEFAULT_QUANTITY);
    }

    @Test
    @DisplayName("생성 시 quantity가 0이면 ORD-005 예외가 발생한다")
    void create_zeroQuantity_throwsORD005() {
        List<OrderItemData> items = List.of(new OrderItemData(1L, 0, 10_000L));

        assertThatThrownBy(() -> Order.create(1L, "ORD-TEST", "홍길동", "010", "12345", "서울", items))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_005);
    }

    @Test
    @DisplayName("생성 시 unitPrice가 음수면 IllegalArgumentException이 발생한다")
    void create_negativeUnitPrice_throwsException() {
        List<OrderItemData> items = List.of(new OrderItemData(1L, 1, -1L));

        assertThatThrownBy(() -> Order.create(1L, "ORD-TEST", "홍길동", "010", "12345", "서울", items))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("unitPrice가 0이면 정상 생성된다")
    void create_zeroUnitPrice_success() {
        List<OrderItemData> items = List.of(new OrderItemData(1L, 1, 0L));
        Order order = Order.create(1L, "ORD-TEST", "홍길동", "010", "12345", "서울", items);

        assertThat(order.getOrderItems().get(0).getUnitPrice()).isZero();
        assertThat(order.getTotalAmount()).isZero();
    }
}
