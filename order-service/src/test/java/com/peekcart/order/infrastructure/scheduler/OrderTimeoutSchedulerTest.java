package com.peekcart.order.infrastructure.scheduler;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.infrastructure.metrics.OrderSagaMetrics;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.OrderFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

@ServiceTest
@DisplayName("OrderTimeoutScheduler 단위 테스트")
class OrderTimeoutSchedulerTest {

    @InjectMocks OrderTimeoutScheduler orderTimeoutScheduler;
    @Mock OrderRepository orderRepository;
    @Mock OrderCommandService orderCommandService;
    // 취소 건수를 사유별로 세는 메트릭. 여기서는 스케줄러 로직만 보므로 mock 이면 충분하다.
    @Mock OrderSagaMetrics sagaMetrics;

    @Test
    @DisplayName("[SAGA-TIMEOUT-PAYMENT-PENDING] 만료 주문이 있으면 건별로 cancelExpiredOrder를 호출한다")
    void cancelExpiredOrders_withExpiredOrders() {
        Order order1 = OrderFixture.paymentRequestedOrderWithId();
        Order order2 = OrderFixture.paymentRequestedOrderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));

        orderTimeoutScheduler.cancelExpiredOrders();

        then(orderCommandService).should().cancelExpiredOrder(order1.getId());
        then(orderCommandService).should().cancelExpiredOrder(order2.getId());
        // 계측 배선 검증 — 이 verify 가 없으면 스케줄러에서 계측 호출을 지워도 통과한다.
        then(sagaMetrics).should().timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_PAYMENT, 2);
    }

    @Test
    @DisplayName("만료 주문이 없으면 cancelExpiredOrder를 호출하지 않는다")
    void cancelExpiredOrders_noExpiredOrders() {
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of());

        orderTimeoutScheduler.cancelExpiredOrders();

        then(orderCommandService).should(never()).cancelExpiredOrder(any());
        then(sagaMetrics).should().timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_PAYMENT, 0);
    }

    @Test
    @DisplayName("한 건 실패해도 나머지는 처리된다")
    void cancelExpiredOrders_oneFailsOthersContinue() {
        Order order1 = OrderFixture.paymentRequestedOrderWithId();
        Order order2 = OrderFixture.paymentRequestedOrderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));
        willThrow(new RuntimeException("DB error")).given(orderCommandService).cancelExpiredOrder(order1.getId());

        orderTimeoutScheduler.cancelExpiredOrders();

        then(orderCommandService).should(times(1)).cancelExpiredOrder(order1.getId());
        then(orderCommandService).should(times(1)).cancelExpiredOrder(order2.getId());
        // 실패 건은 세지 않는다 — 취소되지 않은 것을 세면 메트릭이 실제 건수를 부풀린다.
        then(sagaMetrics).should().timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_PAYMENT, 1);
    }

    @Test
    @DisplayName("상태 경합(OrderException)이 발생해도 나머지는 처리된다")
    void cancelExpiredOrders_orderExceptionSkipsAndContinues() {
        Order order1 = OrderFixture.paymentRequestedOrderWithId();
        Order order2 = OrderFixture.paymentRequestedOrderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));
        willThrow(new OrderException(ErrorCode.ORD_003)).given(orderCommandService).cancelExpiredOrder(order1.getId());

        orderTimeoutScheduler.cancelExpiredOrders();

        then(orderCommandService).should(times(1)).cancelExpiredOrder(order1.getId());
        then(orderCommandService).should(times(1)).cancelExpiredOrder(order2.getId());
        // 상태 경합으로 스킵된 건은 취소가 아니다.
        then(sagaMetrics).should().timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_PAYMENT, 1);
    }

    @Test
    @DisplayName("[SAGA-TIMEOUT-OPTIMISTIC-LOSS] 낙관 락 충돌은 재시도하지 않고 포기한다 — 결제 완료가 이긴 경우다 (계획 P2 충돌 정책)")
    void cancelExpiredOrders_optimisticLockConflictGivesUp() {
        Order order1 = OrderFixture.paymentRequestedOrderWithId();
        Order order2 = OrderFixture.paymentRequestedOrderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredPaymentRequested(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));
        willThrow(new OptimisticLockingFailureException("version conflict"))
                .given(orderCommandService).cancelExpiredOrder(order1.getId());

        orderTimeoutScheduler.cancelExpiredOrders();

        // 충돌 건은 1회만 호출(재시도 없음)하고, 잡 전체는 계속 진행한다.
        then(orderCommandService).should(times(1)).cancelExpiredOrder(order1.getId());
        then(orderCommandService).should(times(1)).cancelExpiredOrder(order2.getId());
        then(sagaMetrics).should().timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_PAYMENT, 1);
    }

    @Test
    @DisplayName("[SAGA-TIMEOUT-UNCONFIRMED-RESERVATION] 예약 미확정 잡: 취소 건수를 unconfirmed_reservation 사유로만 센다")
    void cancelUnconfirmedReservations_countsOwnReason() {
        Order order1 = OrderFixture.orderWithId();
        given(orderRepository.findUnconfirmedReservationBefore(any(LocalDateTime.class)))
                .willReturn(List.of(order1));

        orderTimeoutScheduler.cancelUnconfirmedReservations();

        then(orderCommandService).should().cancelExpiredOrder(order1.getId());
        then(sagaMetrics).should()
                .timeoutCancelled(OrderSagaMetrics.REASON_UNCONFIRMED_RESERVATION, 1);
        then(sagaMetrics).should(never())
                .timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_PAYMENT, 1);
        then(sagaMetrics).should(never())
                .timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_LEASE, 1);
    }

    @Test
    @DisplayName("[SAGA-TIMEOUT-LEASE-EXPIRED] lease 만료 잡: 만료된 예약 주문을 건별로 취소한다 (계획 P3)")
    void cancelExpiredReservationLeases_cancelsEach() {
        Order order1 = OrderFixture.orderWithId();
        Order order2 = OrderFixture.orderWithId();
        ReflectionTestUtils.setField(order2, "id", 2L);
        given(orderRepository.findExpiredReservationLease(any(LocalDateTime.class)))
                .willReturn(List.of(order1, order2));

        orderTimeoutScheduler.cancelExpiredReservationLeases();

        then(orderCommandService).should().cancelExpiredOrder(order1.getId());
        then(orderCommandService).should().cancelExpiredOrder(order2.getId());
        // 사유가 섞이면 어느 잡이 도는지 구분되지 않는다 — lease 잡은 lease 사유로만 센다.
        then(sagaMetrics).should().timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_LEASE, 2);
        then(sagaMetrics).should(never())
                .timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_PAYMENT, 2);
    }

    @Test
    @DisplayName("lease 만료 잡: 대상이 없으면 아무것도 취소하지 않는다")
    void cancelExpiredReservationLeases_noneExpired() {
        given(orderRepository.findExpiredReservationLease(any(LocalDateTime.class)))
                .willReturn(List.of());

        orderTimeoutScheduler.cancelExpiredReservationLeases();

        then(orderCommandService).should(never()).cancelExpiredOrder(any());
        then(sagaMetrics).should().timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_LEASE, 0);
    }
}
