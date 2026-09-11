package com.peekcart.order.infrastructure.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.infrastructure.OrderJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V-28d(order 축) — 사전조건 판정표를 {@code reserved} × {@link OrderStatus} <b>8종 전 조합</b>으로 관통한다
 * (계획 리뷰 2R #4 · 3R #4).
 *
 * <p>초안 표는 `PENDING`/`CANCELLED` 만 정의하고 존재하지도 않는 `PAID` 를 적었다 — 나머지 상태의
 * allow/deny 가 미정이라 무엇을 구현해도 통과했다.
 */
class OrderReplayPreconditionTest {

    private static final String TOPIC = "stock.reservation.result";

    private final OrderJpaRepository orderJpaRepository = mock(OrderJpaRepository.class);
    private final OrderReplayPrecondition precondition =
            new OrderReplayPrecondition(orderJpaRepository, new ObjectMapper());

    private static String payload(boolean reserved) {
        return """
                {"eventId":"e-1","eventType":"stock.reservation.result",
                 "payload":{"orderId":1,"reserved":%s}}""".formatted(reserved);
    }

    private void givenOrder(OrderStatus status, LocalDateTime reservationConfirmedAt) {
        Order order = mock(Order.class);
        when(order.getStatus()).thenReturn(status);
        when(order.getReservationConfirmedAt()).thenReturn(reservationConfirmedAt);
        when(orderJpaRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.of(order));
    }

    @Test
    @DisplayName("reserved=true · PENDING · 미확정 → allow (유일한 allow 조합)")
    void reservedTrueOnPendingUnconfirmed() {
        givenOrder(OrderStatus.PENDING, null);

        assertThat(precondition.reject(TOPIC, payload(true))).isNull();
    }

    @Test
    @DisplayName("reserved=true · PENDING 이나 이미 확정됨 → deny (재적용이 확정 시각을 되돌린다)")
    void reservedTrueOnAlreadyConfirmed() {
        givenOrder(OrderStatus.PENDING, LocalDateTime.now());

        assertThat(precondition.reject(TOPIC, payload(true))).contains("이미 예약이 확정");
    }

    @ParameterizedTest(name = "reserved=true · {0} → deny")
    @EnumSource(value = OrderStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    @DisplayName("reserved=true 는 PENDING 이 아닌 전 상태에서 deny")
    void reservedTrueOnNonPending(OrderStatus status) {
        givenOrder(status, null);

        assertThat(precondition.reject(TOPIC, payload(true)))
                .as("%s 에서 예약 확정을 재적용하면 안 된다", status)
                .isNotNull();
    }

    @ParameterizedTest(name = "reserved=false · {0} → allow")
    @EnumSource(value = OrderStatus.class, names = {"PENDING", "PAYMENT_REQUESTED", "PAYMENT_FAILED", "CANCELLED"})
    @DisplayName("reserved=false 는 cancel() 이 전이 가능한 상태 + 이미 CANCELLED 에서 allow")
    void reservedFalseOnCancellable(OrderStatus status) {
        givenOrder(status, null);

        assertThat(precondition.reject(TOPIC, payload(false))).isNull();
    }

    @ParameterizedTest(name = "reserved=false · {0} → deny")
    @EnumSource(value = OrderStatus.class, names = {"PAYMENT_COMPLETED", "PREPARING", "SHIPPED", "DELIVERED"})
    @DisplayName("reserved=false 는 cancel() 이 예외가 되는 상태에서 deny (재-DLQ 를 만들지 않는다)")
    void reservedFalseOnNonCancellable(OrderStatus status) {
        givenOrder(status, null);

        assertThat(precondition.reject(TOPIC, payload(false))).contains("취소로 전이할 수 없는 상태");
    }

    @Test
    @DisplayName("판정표의 취소 가능 집합이 실제 상태머신과 일치한다")
    void cancellableSetMatchesRealStateMachine() {
        // mock 으로 상태를 흉내내므로, 표의 근거인 "cancel() 이 전이 가능한가" 는 실제 enum 으로 확인한다.
        // 이 단언이 없으면 상태머신이 바뀌어도 이 테스트는 계속 green 이다.
        Set<OrderStatus> realCancellable = java.util.Arrays.stream(OrderStatus.values())
                .filter(s -> s.canTransitionTo(OrderStatus.CANCELLED))
                .collect(java.util.stream.Collectors.toSet());

        assertThat(realCancellable).containsExactlyInAnyOrder(
                OrderStatus.PENDING, OrderStatus.PAYMENT_REQUESTED, OrderStatus.PAYMENT_FAILED);
    }

    @Test
    @DisplayName("주문 부재 → deny (fail-closed)")
    void orderMissing() {
        when(orderJpaRepository.findByIdForUpdate(anyLong())).thenReturn(Optional.empty());

        assertThat(precondition.reject(TOPIC, payload(true))).contains("주문이 없다");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"payload\":{\"reserved\":true}}",
            "{\"payload\":{\"orderId\":1}}",
            "{\"payload\":{\"orderId\":\"문자열\",\"reserved\":true}}",
            "{\"payload\":{\"orderId\":1,\"reserved\":\"참\"}}"
    })
    @DisplayName("필드 부재·타입 오류 → deny (소비 코드는 NPE 로 터진다 — 그 실패를 재생산하지 않는다)")
    void malformedPayload(String body) {
        assertThat(precondition.reject(TOPIC, body)).contains("orderId/reserved");
    }

    @Test
    @DisplayName("파싱 실패 → deny")
    void unparseablePayload() {
        assertThat(precondition.reject(TOPIC, "{not json")).contains("파싱하지 못했다");
    }

    @Test
    @DisplayName("사전조건 대상이 아닌 토픽이 들어오면 deny (배선 오류를 조용히 통과시키지 않는다)")
    void wrongTopic() {
        assertThat(precondition.reject("order.created", payload(true))).contains("판정 대상이 아닌 토픽");
    }
}
