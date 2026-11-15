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
 * 예약 결과(stock.reservation.result reserved=true) 선도착 marker (D-112).
 * Payment 가 아직 없을 때 재시도로 파티션을 막는 대신 orderId 기준으로 영속하고,
 * Payment 생성 시점에 결제 준비로 적용한다.
 */
@Entity
@Table(name = "payment_reservation_results")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentReservationResult {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    /** lease 만료 시각. 구 메시지(lease 미부여)면 {@code null} (ADR-0012 D2). */
    @Column(name = "reservation_expires_at")
    private LocalDateTime reservationExpiresAt;

    @Column(name = "received_at", nullable = false)
    private LocalDateTime receivedAt;

    private PaymentReservationResult(Long orderId, LocalDateTime reservationExpiresAt) {
        this.orderId = orderId;
        this.reservationExpiresAt = reservationExpiresAt;
        this.receivedAt = LocalDateTime.now();
    }

    public static PaymentReservationResult of(Long orderId, LocalDateTime reservationExpiresAt) {
        return new PaymentReservationResult(orderId, reservationExpiresAt);
    }
}
