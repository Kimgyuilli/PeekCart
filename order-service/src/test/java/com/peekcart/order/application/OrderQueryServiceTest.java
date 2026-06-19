package com.peekcart.order.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.application.dto.OrderSummaryDto;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ServiceTest
@DisplayName("OrderQueryService 단위 테스트")
class OrderQueryServiceTest {

    @InjectMocks OrderQueryService orderQueryService;
    @Mock OrderRepository orderRepository;

    @Test
    @DisplayName("getOrders: 주문 목록을 페이징으로 반환한다")
    void getOrders_success() {
        Order order = OrderFixture.orderWithId();
        Pageable pageable = PageRequest.of(0, 10);
        given(orderRepository.findByUserId(OrderFixture.DEFAULT_USER_ID, pageable))
                .willReturn(new PageImpl<>(List.of(order)));

        Page<OrderSummaryDto> result = orderQueryService.getOrders(OrderFixture.DEFAULT_USER_ID, pageable);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).orderNumber()).isEqualTo(OrderFixture.DEFAULT_ORDER_NUMBER);
    }

    @Test
    @DisplayName("getOrder: 주문 상세를 반환한다")
    void getOrder_success() {
        Order order = OrderFixture.orderWithId();
        given(orderRepository.findByIdAndUserId(OrderFixture.DEFAULT_ORDER_ID, OrderFixture.DEFAULT_USER_ID))
                .willReturn(Optional.of(order));

        OrderDetailDto result = orderQueryService.getOrder(OrderFixture.DEFAULT_USER_ID, OrderFixture.DEFAULT_ORDER_ID);

        assertThat(result.orderNumber()).isEqualTo(OrderFixture.DEFAULT_ORDER_NUMBER);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    @DisplayName("getOrder: 주문이 없으면 ORD-001 예외가 발생한다")
    void getOrder_notFound_throwsORD001() {
        given(orderRepository.findByIdAndUserId(99L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> orderQueryService.getOrder(1L, 99L))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(ErrorCode.ORD_001);
    }
}
