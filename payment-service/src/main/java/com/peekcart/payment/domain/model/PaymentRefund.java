package com.peekcart.payment.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 환불 원장 (ADR-0018 D2). {@code order_id} 유니크가 "동일 논리 환불 1건" fence 다.
 *
 * <p><b>행 생성은 이 엔티티로 하지 않는다</b> — fence 획득은 단일 원자 쿼리(INSERT ... ON DUPLICATE KEY)
 * 여야 하며, JPA {@code save} 의 유니크 위반은 flush 시점에 터져 소비 트랜잭션을 rollback-only 로
 * 만든다. 이 엔티티는 <b>claim 이후의 상태 전이와 감사 필드</b>를 담당한다.
 */
@Entity
@Table(name = "payment_refunds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PaymentRefund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "payment_key", nullable = false, length = 200)
    private String paymentKey;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Column(nullable = false)
    private int attempts;

    /**
     * fencing token — claim 마다 증가한다. lease 가 만료된 옛 owner 가 뒤늦게 확정하려 할 때
     * 자기가 claim 하던 generation 과 현재 값이 달라 무효가 된다(ADR-0018 D3 crash matrix b).
     */
    @Column(nullable = false)
    private long generation;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;

    @Column(name = "last_error", length = 500)
    private String lastError;

    @Column(name = "pg_response", columnDefinition = "TEXT")
    private String pgResponse;

    @Column(name = "failure_code", length = 100)
    private String failureCode;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 100)
    private String resolvedBy;

    @Column(name = "resolution_reason", length = 500)
    private String resolutionReason;

    /** PG 취소 성공 확정. */
    public void markSucceeded(String pgResponse) {
        transitionTo(RefundStatus.SUCCEEDED);
        this.pgResponse = truncate(pgResponse, 65_000);
        this.resolvedAt = LocalDateTime.now();
    }

    /**
     * 영구 실패 확정 — 재시도로 상태가 바뀌지 않는 사유일 때만 호출한다.
     * {@code failureCode} 는 회신 payload 의 필수 필드라(ADR-0018 D1) 비어 있으면 대체값을 채운다.
     */
    public void markFailed(String failureCode, String lastError) {
        transitionTo(RefundStatus.FAILED);
        this.failureCode = truncate(isBlank(failureCode) ? "UNKNOWN_PERMANENT_FAILURE" : failureCode, 100);
        this.lastError = truncate(lastError, 500);
        this.resolvedAt = LocalDateTime.now();
    }

    /** 결과 불명 — 종결이 아니며 reconciliation 대상으로 남는다. */
    public void markUnresolved(String lastError) {
        transitionTo(RefundStatus.UNRESOLVED);
        this.lastError = truncate(lastError, 500);
    }

    /**
     * 운영자 수동 종결 (ADR-0018 D2 — 조회 상한 초과). 감사 필드가 없으면 종결시키지 않는다.
     *
     * @throws PaymentException actor/사유가 비었으면 {@code PAY-004}
     */
    public void resolveManually(String resolvedBy, String reason) {
        if (isBlank(resolvedBy) || isBlank(reason)) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        transitionTo(RefundStatus.FAILED);
        this.resolvedBy = truncate(resolvedBy, 100);
        this.resolutionReason = truncate(reason, 500);
        this.failureCode = "MANUALLY_RESOLVED";
        this.resolvedAt = LocalDateTime.now();
    }

    /** 결과 불명이 이어질 때 마지막 오류만 갱신한다(상태 전이 없음). */
    public void recordLastError(String lastError) {
        this.lastError = truncate(lastError, 500);
    }

    /**
     * 이번 실행의 PG 호출 시도 횟수를 <b>누적</b>한다(감사 · 재시도 소진 판정).
     * 대입이 아니라 누적인 이유: reconciliation 이 조회만 하고 끝나면 증분이 0 인데, 대입하면
     * 앞서 소진한 3회 기록이 0 으로 지워진다.
     */
    public void recordAttempts(int attemptsDelta) {
        this.attempts += attemptsDelta;
    }

    /** 이 확정 시도가 현재 소유권(generation)에 해당하는가. 아니면 만료된 owner 의 뒤늦은 확정이다. */
    public boolean ownsGeneration(long generation) {
        return this.generation == generation;
    }

    /** 이미 종결된 원장인가 — 회신 재발행/중복 확정 판단에 쓴다. */
    public boolean isTerminal() {
        return this.status.isTerminal();
    }

    private void transitionTo(RefundStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        this.status = target;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
