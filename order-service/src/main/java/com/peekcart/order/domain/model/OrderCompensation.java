package com.peekcart.order.domain.model;

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
import java.time.temporal.ChronoUnit;

/**
 * 주문 보상 원장. "보상이 필요하다"를 <b>영속</b> 사실로 남긴다.
 *
 * <p>알림(Slack)은 부가 신호일 뿐 종료 상태의 근거가 될 수 없다 — order-service 의 {@code SlackPort}
 * 는 배포 구성상 no-op 이고, 소비 트랜잭션이 커밋되면 {@code processed_events} 때문에 같은 이벤트를
 * 다시 소비할 수도 없다. 환불 요청 경로는 이 원장을 입력으로 삼는다.
 */
@Entity
@Table(name = "order_compensations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderCompensation {

    /** 실패 회신에 사유 코드가 없을 때 쓰는 대체값 — 근거 없는 미해결 종착을 만들지 않는다. */
    private static final String UNKNOWN_FAILURE_CODE = "UNKNOWN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private CompensationReason reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompensationStatus status;

    @Column(length = 500)
    private String detail;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    /** 환불 영구 실패 사유 코드 ({@code REFUND_FAILED} 일 때만). */
    @Column(name = "failure_code", length = 60)
    private String failureCode;

    private OrderCompensation(Long orderId, CompensationReason reason, String detail) {
        this.orderId = orderId;
        this.reason = reason;
        this.status = CompensationStatus.OPEN;
        this.detail = detail;
        // 저장소 정밀도(DATETIME(6))로 맞춰 기록한다. MySQL 은 초과 자릿수를 truncate 가 아니라
        // 반올림하므로, 나노초를 그대로 두면 인메모리 값과 저장된 값이 최대 1μs 어긋난다.
        // 같은 값이 order.compensation.requested 의 detectedAt 으로도 실리므로(ADR-0018 D1),
        // 여기서 확정해야 "원장과 이벤트가 같은 사실"이 근사가 아니라 등식이 된다.
        this.detectedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** 미해소(OPEN) 보상 건을 생성한다. 종결 전이는 {@code payment.refunded} 회신이 수행한다. */
    public static OrderCompensation open(Long orderId, CompensationReason reason, String detail) {
        return new OrderCompensation(orderId, reason, detail);
    }

    /**
     * 환불 성공 회신으로 해소한다 (ADR-0018 D4). 이미 종결된 건은 no-op —
     * 회신이 재전달돼도(DLQ 재발행·재소비) 종착 상태와 시각이 흔들리지 않는다.
     */
    public void resolveByRefund(LocalDateTime resolvedAt) {
        if (isClosed()) {
            return;
        }
        this.status = CompensationStatus.RESOLVED;
        this.resolvedAt = resolvedAt;
    }

    /**
     * 환불 영구 실패 회신으로 닫는다 (ADR-0018 D4). <b>해결됨이 아니다</b> — 운영이 처리할
     * 대상으로 남기며 사유 코드를 함께 기록한다.
     *
     * <p>사유 코드가 비면 {@code UNKNOWN} 으로 정규화한다 — 미해결 종착은 운영이 보고 처리해야 하는데
     * 근거 없이 고정되면 무엇을 처리해야 할지 알 수 없고, {@link #isClosed()} 때문에 이후 정상 회신도
     * no-op 이라 되돌릴 수 없다. 값을 비워 두느니 "모른다"를 명시적으로 남긴다.
     */
    public void failByRefund(String failureCode, LocalDateTime resolvedAt) {
        if (isClosed()) {
            return;
        }
        this.status = CompensationStatus.REFUND_FAILED;
        this.failureCode = (failureCode == null || failureCode.isBlank()) ? UNKNOWN_FAILURE_CODE : failureCode;
        this.resolvedAt = resolvedAt;
    }

    /** 종착 여부. {@code OPEN} 만 미종결이다. */
    public boolean isClosed() {
        return status != CompensationStatus.OPEN;
    }
}
