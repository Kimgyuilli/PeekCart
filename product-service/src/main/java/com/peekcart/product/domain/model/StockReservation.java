package com.peekcart.product.domain.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 재고 예약 원장 엔티티 (ADR-0012 D3, strangler-1).
 * orderId 단위 상태머신으로 비동기 예약/복구의 멱등·순서를 보장한다.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation {

    /** 환불 종결 결과 — {@code RefundResult}(:common) 의 확정 값과 이름을 맞춘다. */
    private static final String REFUND_SUCCEEDED = "SUCCEEDED";
    private static final String REFUND_FAILED = "FAILED";
    private static final String UNKNOWN_FAILURE_CODE = "UNKNOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReservationStatus status;

    @Column(columnDefinition = "TEXT")
    private String items;

    @Column(name = "source_event_id", unique = true, length = 36)
    private String sourceEventId;

    @Column(name = "reserved_at")
    private LocalDateTime reservedAt;

    /**
     * 예약 lease 만료 시각 (계획 P4). Product 가 부여하고 {@code stock.reservation.result} 로 공유한다.
     * sweeper 는 이 시각(+유예) 을 근거로만 회수하며, null 이면 회수 대상이 아니다(기존 행 안전측).
     */
    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "confirmed_at")
    private LocalDateTime confirmedAt;

    @Column(name = "released_at")
    private LocalDateTime releasedAt;

    @Column(name = "compensated_at")
    private LocalDateTime compensatedAt;

    /**
     * 환불 결과 (ADR-0018 D4). {@code compensatedAt} 은 <b>감지 marker 이지 종결 표시가 아니다</b> —
     * 둘을 한 컬럼에 섞으면 "감지했으나 환불 실패"와 "환불 완료"를 구분할 수 없다.
     * 회신 전이면 null 이다.
     */
    @Column(name = "refund_result", length = 20)
    private String refundResult;

    @Column(name = "refund_resolved_at")
    private LocalDateTime refundResolvedAt;

    /** 환불 영구 실패 사유 코드 ({@code refundResult=FAILED} 일 때만). */
    @Column(name = "refund_failure_code", length = 60)
    private String refundFailureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private StockReservation(Long orderId, ReservationStatus status, String items, String sourceEventId,
                             Duration leaseTtl) {
        this.orderId = orderId;
        this.status = status;
        this.items = items;
        this.sourceEventId = sourceEventId;
        this.createdAt = LocalDateTime.now();
        if (status == ReservationStatus.RESERVED) {
            this.reservedAt = this.createdAt;
            this.expiresAt = this.createdAt.plus(leaseTtl);
        }
    }

    /**
     * 예약 성공 원장.
     *
     * @param leaseTtl 예약 유효기간. 만료 시각이 {@code stock.reservation.result} 로 공유된다
     */
    public static StockReservation reserved(Long orderId, String itemsJson, String sourceEventId, Duration leaseTtl) {
        return new StockReservation(orderId, ReservationStatus.RESERVED, itemsJson, sourceEventId, leaseTtl);
    }

    /** 재고 부족으로 실패한 원장. */
    public static StockReservation failed(Long orderId, String itemsJson, String sourceEventId) {
        return new StockReservation(orderId, ReservationStatus.FAILED, itemsJson, sourceEventId, Duration.ZERO);
    }

    /**
     * 환불 결과 회신을 종결로 기록한다 (ADR-0018 D4). 이미 기록됐으면 no-op —
     * 회신 재전달(DLQ replay·상류 재발행 등)에도 종착 결과와 시각이 흔들리지 않는다.
     *
     * <p><b>결과별 불변식을 여기서 강제한다</b>: 성공에는 사유 코드가 없고 실패에는 반드시 있다.
     * 소비자가 {@code result} 만 거르면 "성공인데 실패 사유가 있는" 모순 상태가 원장에 남을 수 있고,
     * 그런 원장은 읽는 사람에게 거짓을 말한다. 사유 코드가 빈 실패는 {@code UNKNOWN} 으로 정규화한다 —
     * 종결을 거부하면 미해결이 영구히 남는 쪽이 더 나쁘다.
     *
     * @param result      확정된 결과만 허용 ({@code SUCCEEDED} / {@code FAILED})
     * @param failureCode 실패 사유 코드 (성공 시 무시)
     * @throws IllegalArgumentException 확정 결과가 아니거나 {@code resolvedAt} 이 없을 때
     */
    public void recordRefundResult(String result, String failureCode, LocalDateTime resolvedAt) {
        if (!REFUND_SUCCEEDED.equals(result) && !REFUND_FAILED.equals(result)) {
            throw new IllegalArgumentException("확정된 환불 결과가 아님: " + result);
        }
        if (resolvedAt == null) {
            throw new IllegalArgumentException("환불 종결 시각이 없음 — orderId=" + orderId);
        }
        if (this.refundResult != null) {
            return;
        }
        this.refundResult = result;
        this.refundFailureCode = REFUND_SUCCEEDED.equals(result)
                ? null
                : (failureCode == null || failureCode.isBlank() ? UNKNOWN_FAILURE_CODE : failureCode);
        this.refundResolvedAt = resolvedAt;
    }

    /** 예약 도착 전 취소가 먼저 온 tombstone. */
    public static StockReservation cancelTombstone(Long orderId) {
        return new StockReservation(orderId, ReservationStatus.CANCEL_REQUESTED, null, null, Duration.ZERO);
    }
}
