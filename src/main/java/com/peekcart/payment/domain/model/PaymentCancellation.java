package com.peekcart.payment.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 결제 시작 전 주문 취소(order.cancelled) marker.
 * order.cancelled 가 order.created 보다 선도착해 Payment 가 아직 없을 때 orderId 기준으로 취소를 영속 기록하고,
 * Payment 생성 시점에 즉시 CANCELLED 로 적용해 취소 게이트 누수(silent charge)를 막는다.
 */
@Entity
@Table(name = "payment_cancellations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentCancellation {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    @Column(name = "cancelled_at", nullable = false)
    private LocalDateTime cancelledAt;

    private PaymentCancellation(Long orderId) {
        this.orderId = orderId;
        this.cancelledAt = LocalDateTime.now();
    }

    public static PaymentCancellation of(Long orderId) {
        return new PaymentCancellation(orderId);
    }
}
