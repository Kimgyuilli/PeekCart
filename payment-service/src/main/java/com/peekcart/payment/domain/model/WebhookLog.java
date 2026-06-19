package com.peekcart.payment.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Toss 웹훅 수신 로그. idempotency_key를 통해 중복 처리를 방지한다.
 */
@Entity
@Table(name = "webhook_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_key", nullable = false)
    private String paymentKey;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private String status;

    @Column(name = "received_at", nullable = false, updatable = false)
    private LocalDateTime receivedAt;

    private WebhookLog(String paymentKey, String eventType, String idempotencyKey, String payload, String status) {
        this.paymentKey = paymentKey;
        this.eventType = eventType;
        this.idempotencyKey = idempotencyKey;
        this.payload = payload;
        this.status = status;
        this.receivedAt = LocalDateTime.now();
    }

    public static WebhookLog create(String paymentKey, String eventType, String idempotencyKey, String payload, String status) {
        return new WebhookLog(paymentKey, eventType, idempotencyKey, payload, status);
    }
}
