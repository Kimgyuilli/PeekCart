package com.peekcart.order.infrastructure.metrics;

import com.peekcart.global.metrics.CommitAwareMetrics;
import com.peekcart.order.domain.model.CompensationStatus;
import com.peekcart.order.domain.repository.OrderCompensationRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * order-service 의 saga 경로 메트릭.
 *
 * <p><b>타임아웃 취소 3종을 {@code reason} 으로 가른다.</b> 한 값으로 합치면 어느 잡이 도는지
 * 구분되지 않는다 — 결제 만료·예약 미확정·lease 만료는 원인도 대응도 다르다. 합쳐놓으면
 * "취소가 늘었다" 까지만 알고 왜인지는 다시 로그를 읽어야 한다.
 *
 * <p><b>증가는 커밋 이후다</b>({@link CommitAwareMetrics}) — 보상 적재는 소비 트랜잭션 안에서
 * 일어나므로, 본문에서 바로 올리면 이후 커밋이 실패해도 카운터만 남는다.
 *
 * <p><b>보상은 Counter 와 Gauge 를 함께 둔다.</b> 누적 Counter 로는 "지금 몇 건이 미해소인가" 를
 * 알 수 없어 alert 를 만들 데이터가 없다. 잔량은 원장을 직접 세는 Gauge 다
 * ({@code payment.refund.backlog} 가 같은 이유로 Gauge 인 것과 같다).
 */
@Component
public class OrderSagaMetrics {

    /** 결제 요청 후 15분 만료. */
    public static final String REASON_EXPIRED_PAYMENT = "expired_payment";
    /** 예약 확정 없이 5분 경과한 PENDING. */
    public static final String REASON_UNCONFIRMED_RESERVATION = "unconfirmed_reservation";
    /** Product 가 부여한 lease 가 만료됐는데 결제 미시작. */
    public static final String REASON_EXPIRED_LEASE = "expired_lease";

    private final Counter timeoutExpiredPayment;
    private final Counter timeoutUnconfirmedReservation;
    private final Counter timeoutExpiredLease;
    private final Counter compensationDetected;

    public OrderSagaMetrics(MeterRegistry registry, OrderCompensationRepository compensationRepository) {
        this.timeoutExpiredPayment = timeoutCounter(registry, REASON_EXPIRED_PAYMENT);
        this.timeoutUnconfirmedReservation = timeoutCounter(registry, REASON_UNCONFIRMED_RESERVATION);
        this.timeoutExpiredLease = timeoutCounter(registry, REASON_EXPIRED_LEASE);

        this.compensationDetected = Counter.builder("saga.compensation.detected")
                .description("PAID_BUT_CANCELLED 보상 감지 (원장 신규 적재분만)")
                .register(registry);

        // 잔량 Gauge — alert 의 데이터 series. 원장을 직접 센다.
        Gauge.builder("saga.compensation.backlog",
                        compensationRepository, r -> r.countByStatus(CompensationStatus.OPEN))
                .tag("status", "open")
                .description("미해소 보상 원장 건수")
                .register(registry);
        Gauge.builder("saga.compensation.backlog",
                        compensationRepository, r -> r.countByStatus(CompensationStatus.REFUND_FAILED))
                .tag("status", "refund_failed")
                .description("환불 영구 실패로 닫힌 보상 원장 건수 — 해결됨이 아니다")
                .register(registry);
    }

    private static Counter timeoutCounter(MeterRegistry registry, String reason) {
        return Counter.builder("saga.order.timeout.cancel")
                .tag("reason", reason)
                .description("타임아웃 주문 취소 (사유별)")
                .register(registry);
    }

    /** 타임아웃 취소 — 잡별로 <b>취소한 건수만큼</b>. 실행당 1 이 아니다. */
    public void timeoutCancelled(String reason, int count) {
        if (count <= 0) {
            return;
        }
        switch (reason) {
            case REASON_EXPIRED_PAYMENT -> CommitAwareMetrics.increment(timeoutExpiredPayment, count);
            case REASON_UNCONFIRMED_RESERVATION -> CommitAwareMetrics.increment(timeoutUnconfirmedReservation, count);
            case REASON_EXPIRED_LEASE -> CommitAwareMetrics.increment(timeoutExpiredLease, count);
            default -> throw new IllegalArgumentException("알 수 없는 취소 사유: " + reason);
        }
    }

    /** 보상 원장 <b>신규</b> 적재. 이미 열려 있던 건의 no-op 은 제외. */
    public void compensationDetected() {
        CommitAwareMetrics.increment(compensationDetected);
    }
}
