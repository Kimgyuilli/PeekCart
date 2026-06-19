package com.peekcart.order.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OrderStatus 상태 전이 단위 테스트")
class OrderStatusTest {

    @Test
    @DisplayName("PENDING → PAYMENT_REQUESTED 전이가 허용된다")
    void pending_toPaymentRequested_allowed() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAYMENT_REQUESTED)).isTrue();
    }

    @Test
    @DisplayName("PENDING → CANCELLED 전이가 허용된다")
    void pending_toCancelled_allowed() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("PENDING → PAYMENT_COMPLETED 전이가 거부된다")
    void pending_toPaymentCompleted_denied() {
        assertThat(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAYMENT_COMPLETED)).isFalse();
    }

    @Test
    @DisplayName("PAYMENT_REQUESTED → PAYMENT_COMPLETED 전이가 허용된다")
    void paymentRequested_toPaymentCompleted_allowed() {
        assertThat(OrderStatus.PAYMENT_REQUESTED.canTransitionTo(OrderStatus.PAYMENT_COMPLETED)).isTrue();
    }

    @Test
    @DisplayName("PAYMENT_REQUESTED → PAYMENT_FAILED 전이가 허용된다")
    void paymentRequested_toPaymentFailed_allowed() {
        assertThat(OrderStatus.PAYMENT_REQUESTED.canTransitionTo(OrderStatus.PAYMENT_FAILED)).isTrue();
    }

    @Test
    @DisplayName("PAYMENT_REQUESTED → CANCELLED 전이가 허용된다")
    void paymentRequested_toCancelled_allowed() {
        assertThat(OrderStatus.PAYMENT_REQUESTED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("PAYMENT_COMPLETED → PREPARING 전이가 허용된다")
    void paymentCompleted_toPreparing_allowed() {
        assertThat(OrderStatus.PAYMENT_COMPLETED.canTransitionTo(OrderStatus.PREPARING)).isTrue();
    }

    @Test
    @DisplayName("PAYMENT_COMPLETED → CANCELLED 전이가 거부된다")
    void paymentCompleted_toCancelled_denied() {
        assertThat(OrderStatus.PAYMENT_COMPLETED.canTransitionTo(OrderStatus.CANCELLED)).isFalse();
    }

    @Test
    @DisplayName("PAYMENT_FAILED → CANCELLED만 허용된다")
    void paymentFailed_toCancelled_allowed() {
        assertThat(OrderStatus.PAYMENT_FAILED.canTransitionTo(OrderStatus.CANCELLED)).isTrue();
    }

    @Test
    @DisplayName("PREPARING → SHIPPED 전이가 허용된다")
    void preparing_toShipped_allowed() {
        assertThat(OrderStatus.PREPARING.canTransitionTo(OrderStatus.SHIPPED)).isTrue();
    }

    @Test
    @DisplayName("SHIPPED → DELIVERED 전이가 허용된다")
    void shipped_toDelivered_allowed() {
        assertThat(OrderStatus.SHIPPED.canTransitionTo(OrderStatus.DELIVERED)).isTrue();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("DELIVERED는 터미널 상태이다 — 어떤 전이도 허용되지 않는다")
    void delivered_isTerminal(OrderStatus target) {
        assertThat(OrderStatus.DELIVERED.canTransitionTo(target)).isFalse();
    }

    @ParameterizedTest
    @EnumSource(OrderStatus.class)
    @DisplayName("CANCELLED는 터미널 상태이다 — 어떤 전이도 허용되지 않는다")
    void cancelled_isTerminal(OrderStatus target) {
        assertThat(OrderStatus.CANCELLED.canTransitionTo(target)).isFalse();
    }
}
