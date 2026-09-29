package com.peekcart.payment.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * 결제 애그리거트 루트. 상태 전이 및 금액 검증 로직을 직접 보유한다.
 * payments 테이블에 created_at만 존재하므로 BaseEntity를 상속하지 않는다.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "payment_key", nullable = false, unique = true)
    private String paymentKey;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    /** reserve→pay 게이트: stock.reservation.result(reserved=true) 수신 시 true (ADR-0012 §D3). */
    @Column(name = "ready_for_payment", nullable = false)
    private boolean readyForPayment;

    /**
     * 공유받은 재고 예약 lease 만료 시각. 이 시각 이후 승인은 거부한다 —
     * Product sweeper 가 만료 예약을 회수한 뒤에도 승인이 통과하면 재판매된 재고와 이중 판매가 된다.
     * null = lease 미수신(구 메시지) → 만료 판정하지 않는다(하위 호환).
     */
    @Column(name = "reservation_expires_at")
    private LocalDateTime reservationExpiresAt;

    @Column
    private String method;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /** approve ↔ cancelBeforePayment 동시 전이의 last-write-wins 차단. */
    @Version
    private long version;

    private Payment(Long orderId, Long userId, long amount) {
        this.orderId = orderId;
        this.userId = userId;
        this.paymentKey = UUID.randomUUID().toString();
        this.amount = amount;
        this.status = PaymentStatus.PENDING;
        this.readyForPayment = false;
        this.createdAt = LocalDateTime.now();
    }

    public static Payment create(Long orderId, Long userId, long amount) {
        return new Payment(orderId, userId, amount);
    }

    /**
     * Toss가 발급한 실제 paymentKey로 교체한다.
     * PENDING 상태에서만 호출 가능하다.
     *
     * @throws PaymentException PENDING 상태가 아니면 {@code PAY-004}
     */
    public void assignPaymentKey(String paymentKey) {
        if (this.status != PaymentStatus.PENDING) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        this.paymentKey = paymentKey;
    }

    /**
     * 결제를 승인 상태로 전이한다.
     *
     * @throws PaymentException PENDING 상태가 아니면 {@code PAY-004}
     */
    public void approve(String method, LocalDateTime approvedAt) {
        if (!this.status.canTransitionTo(PaymentStatus.APPROVED)) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        this.status = PaymentStatus.APPROVED;
        this.method = method;
        this.approvedAt = approvedAt;
    }

    /**
     * 결제를 실패 상태로 전이한다.
     *
     * @throws PaymentException PENDING 상태가 아니면 {@code PAY-004}
     */
    public void fail() {
        if (!this.status.canTransitionTo(PaymentStatus.FAILED)) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        this.status = PaymentStatus.FAILED;
    }

    /**
     * 환불이 확정된 결제를 종결 상태로 전이한다 (ADR-0018 D2).
     * 환불의 <i>진행</i> 상태는 {@code payment_refunds} 원장이 소유하며, 여기에는 확정만 반영한다.
     *
     * @throws PaymentException APPROVED 가 아니면 {@code PAY-004}
     */
    public void markRefunded() {
        if (!this.status.canTransitionTo(PaymentStatus.REFUNDED)) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        this.status = PaymentStatus.REFUNDED;
    }

    /**
     * 요청 금액이 결제 금액과 일치하는지 검증한다.
     *
     * @throws PaymentException 금액 불일치 시 {@code PAY-001}
     */
    public void validateAmount(long requestedAmount) {
        if (this.amount != requestedAmount) {
            throw new PaymentException(ErrorCode.PAY_001);
        }
    }

    /**
     * 결제가 해당 사용자 소유인지 검증한다 (OrderPort.verifyOrderOwner 동기 호출 대체, Seam 1).
     *
     * @throws PaymentException 소유자 불일치 시 {@code PAY-007}
     */
    public void verifyOwner(Long userId) {
        if (this.userId == null || !this.userId.equals(userId)) {
            throw new PaymentException(ErrorCode.PAY_007);
        }
    }

    /**
     * 재고 예약 확정(reserved=true)을 로컬에 반영한다 (reserve→pay 게이트, ADR-0012 §D3).
     *
     * @param reservationExpiresAt 공유받은 lease 만료 시각. null 이면 만료 판정을 하지 않는다(구 메시지)
     */
    public void markReadyForPayment(LocalDateTime reservationExpiresAt) {
        this.readyForPayment = true;
        this.reservationExpiresAt = reservationExpiresAt;
    }

    /**
     * 결제 진행 가능 여부를 검증한다 (동기 {@code markPaymentRequested} 게이트의 payment-로컬 복원).
     *
     * <p><b>이 검사는 fence 가 아니다.</b> 승인은 이 검사 이후 같은 트랜잭션에서 PG 를 호출하므로, 검사와
     * 승인 성공 사이에 Order 의 lease 만료 취소({@code order.cancelled} → Product release → 재고 복구 →
     * 재판매)가 끼어들 수 있다. 그래서 "아직 안 만료"가 아니라 <b>남은 lease 가 승인 소요를 덮을 만큼
     * 넉넉한지</b>를 본다({@code approvalMargin}). 이는 경합 창을 마진 이내로 <b>줄이는</b> 조치이지
     * 제거하는 조치가 아니다 — 진짜 fence(예약을 승인 전용 상태로 CAS 전이) 는 saga 프로토콜 변경이라
     * 별도 ADR 로 다룬다.
     *
     * @param approvalMargin PG 승인에 허용하는 최대 소요 시간. 남은 lease 가 이보다 짧으면 시작하지 않는다
     * @throws PaymentException 취소/종료 상태면 {@code PAY-009}, 예약 미확정이면 {@code PAY-008},
     *                          lease 가 만료됐거나 마진보다 적게 남았으면 {@code PAY-010}
     */
    public void ensureConfirmable(Duration approvalMargin) {
        if (this.status != PaymentStatus.PENDING) {
            throw new PaymentException(ErrorCode.PAY_009);
        }
        if (!this.readyForPayment) {
            throw new PaymentException(ErrorCode.PAY_008);
        }
        if (this.reservationExpiresAt != null
                && LocalDateTime.now().plus(approvalMargin).isAfter(this.reservationExpiresAt)) {
            throw new PaymentException(ErrorCode.PAY_010);
        }
    }

    /**
     * 결제 시작 전 주문 취소(order.cancelled)를 반영한다.
     * PENDING 에서만 CANCELLED 로 종료하고, APPROVED/FAILED/CANCELLED 는 no-op 이다
     * (과금-후-취소는 APPROVED 를 덮지 않고 보상 경로로 수렴 — ADR-0012 D3 ④).
     *
     * @return APPROVED 인데 취소가 도착한 보상 필요 케이스면 true
     */
    public boolean cancelBeforePayment() {
        if (this.status == PaymentStatus.PENDING) {
            this.status = PaymentStatus.CANCELLED;
            return false;
        }
        return this.status == PaymentStatus.APPROVED;
    }
}
