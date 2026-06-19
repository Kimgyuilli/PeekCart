package com.peekcart.payment.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PaymentStatus 상태 전이 규칙 테스트")
class PaymentStatusTest {

    @Test
    @DisplayName("PENDING → APPROVED 전이가 허용된다")
    void pending_toApproved_allowed() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.APPROVED)).isTrue();
    }

    @Test
    @DisplayName("PENDING → FAILED 전이가 허용된다")
    void pending_toFailed_allowed() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.FAILED)).isTrue();
    }

    @Test
    @DisplayName("PENDING → PENDING 전이가 거부된다")
    void pending_toPending_denied() {
        assertThat(PaymentStatus.PENDING.canTransitionTo(PaymentStatus.PENDING)).isFalse();
    }

    @Test
    @DisplayName("APPROVED에서 모든 전이가 거부된다")
    void approved_allTransitions_denied() {
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.APPROVED.canTransitionTo(target)).isFalse();
        }
    }

    @Test
    @DisplayName("FAILED에서 모든 전이가 거부된다")
    void failed_allTransitions_denied() {
        for (PaymentStatus target : PaymentStatus.values()) {
            assertThat(PaymentStatus.FAILED.canTransitionTo(target)).isFalse();
        }
    }
}
