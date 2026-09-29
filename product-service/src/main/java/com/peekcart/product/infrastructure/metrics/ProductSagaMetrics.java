package com.peekcart.product.infrastructure.metrics;

import com.peekcart.global.metrics.CommitAwareMetrics;
import com.peekcart.product.application.port.SagaMetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * product-service 의 saga 경로 메트릭.
 *
 * <p><b>실제 전이가 일어났을 때만 올린다.</b> 예약·복구·확정·보상은 전부 멱등 경로를 갖는다 —
 * CAS 패자, 중복 marker, double-release 는 정상 no-op 이다. 그때도 올리면 메트릭이 실제 사건 수를
 * 부풀리고, 그러면 alert 임계값이 무의미해진다. 호출 지점을 "전이 성공 분기 안" 으로 좁힌 이유다.
 *
 * <p><b>증가는 커밋 이후다</b>({@link CommitAwareMetrics}). 계측 지점이 전부 {@code @Transactional}
 * 안이라, 본문에서 바로 올리면 이후 커밋이 실패했을 때 DB 는 롤백되고 카운터만 남는다.
 *
 * <p>{@code sweeper.reclaimed} 는 <b>실행당 1 이 아니라 회수 건수만큼</b> 올린다 — 정상 운영에서
 * 이 값은 0 이어야 하고(복구 주체는 Order 의 취소다), 0 이 아니면 그 크기가 곧 유실 규모다.
 */
@Component
public class ProductSagaMetrics implements SagaMetricsPort {

    private final Counter reservationSuccess;
    private final Counter reservationFailure;
    private final Counter reservationConfirmed;
    private final Counter reservationReleased;
    private final Counter sweeperReclaimed;
    private final Counter compensationDetected;

    public ProductSagaMetrics(MeterRegistry registry) {
        this.reservationSuccess = Counter.builder("saga.reservation.result")
                .tag("outcome", "success")
                .description("재고 예약 결과")
                .register(registry);
        this.reservationFailure = Counter.builder("saga.reservation.result")
                .tag("outcome", "failure")
                .description("재고 예약 결과")
                .register(registry);
        this.reservationConfirmed = Counter.builder("saga.reservation.confirmed")
                .description("예약 확정(RESERVED → CONFIRMED) 전이 성공")
                .register(registry);
        this.reservationReleased = Counter.builder("saga.reservation.released")
                .description("예약 재고 복구(RESERVED → RELEASED) 전이 성공")
                .register(registry);
        this.sweeperReclaimed = Counter.builder("saga.reservation.sweeper.reclaimed")
                .description("lease 만료 sweeper 가 회수한 예약 수 — 정상 운영에서는 0")
                .register(registry);
        this.compensationDetected = Counter.builder("saga.compensation.detected")
                .description("PAID_BUT_UNRESERVED 보상 감지 (marker CAS 성공분만)")
                .register(registry);
    }

    /** 예약 성공 — 재고 차감과 원장 저장이 실제로 일어났을 때. */
    @Override
    public void reservationSucceeded() {
        CommitAwareMetrics.increment(reservationSuccess);
    }

    /** 예약 실패 — 재고 부족·빈 품목·취소 선도착 등 {@code reserved=false} 로 수렴한 경우. */
    @Override
    public void reservationFailed() {
        CommitAwareMetrics.increment(reservationFailure);
    }

    /** 확정 CAS 1건 성공. 중복 {@code payment.completed} 의 멱등 no-op 은 제외. */
    @Override
    public void reservationConfirmed() {
        CommitAwareMetrics.increment(reservationConfirmed);
    }

    /** 복구 CAS 1건 성공. double-release 는 제외. */
    @Override
    public void reservationReleased() {
        CommitAwareMetrics.increment(reservationReleased);
    }

    /** sweeper 회수 — 건수만큼. 0건이면 호출하지 않는다. */
    @Override
    public void sweeperReclaimed(int count) {
        if (count > 0) {
            CommitAwareMetrics.increment(sweeperReclaimed, count);
        }
    }

    /** 보상 marker CAS 1건 성공. 이미 보상된 건의 no-op 은 제외. */
    @Override
    public void compensationDetected() {
        CommitAwareMetrics.increment(compensationDetected);
    }
}
