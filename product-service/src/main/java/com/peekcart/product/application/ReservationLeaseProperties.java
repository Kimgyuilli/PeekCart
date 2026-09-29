package com.peekcart.product.application;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 재고 예약 lease 정책 (ADR-0012 D3). 동작 정책이므로 base {@code application.yml} 소유(ADR-0007).
 *
 * <p>lease 는 Product 가 부여해 {@code stock.reservation.result} 로 Order/Payment 에 공유한다. 회수 순서는
 * <b>Order 취소(만료 시각) → Product sweeper(만료 + 유예)</b> 여야 한다. 정상 경로에서 재고를 되돌리는 주체는
 * Order 의 취소({@code order.cancelled} → release) 이고 sweeper 는 그 경로가 유실됐을 때만 도는 안전망이므로,
 * sweeper 가 먼저 돌면 살아있는 주문의 재고를 뺏는다.
 *
 * <p>{@code sweeperGrace} 는 "결제 승인 수락 ~ 예약 확정(commit) 반영" 사이의 in-flight 구간보다 커야 한다.
 * 승인은 lease 만료 전에만 허용되므로(Payment 게이트), 이 유예가 그 구간을 덮으면 확정 직전 예약이 회수되지 않는다.
 */
@ConfigurationProperties(prefix = "app.reservation.lease")
@Validated
@Getter
@Setter
public class ReservationLeaseProperties {

    /** 예약 유효기간. 만료 시각이 saga 참여자에게 공유된다. */
    @NotNull
    private Duration ttl;

    /** lease 만료 후 sweeper 회수까지의 유예. Order 취소 경로에 우선권을 준다. */
    @NotNull
    private Duration sweeperGrace;

    /** sweeper 1회 실행당 회수 상한 (원장 증가 시 긴 배치 방지). */
    @Positive
    private int sweeperBatchSize = 200;

    @AssertTrue(message = "app.reservation.lease.sweeper-grace 는 0보다 커야 합니다 — "
            + "0 이면 sweeper 가 Order 취소 경로와 동시에 돌아 살아있는 주문의 재고를 회수할 수 있습니다")
    public boolean isSweeperGracePositive() {
        return sweeperGrace != null && !sweeperGrace.isZero() && !sweeperGrace.isNegative();
    }
}
