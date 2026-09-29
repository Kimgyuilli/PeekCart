package com.peekcart.product.infrastructure.scheduler;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Product 예약 lease sweeper 의 주기·lock 정책 (ADR-0007).
 *
 * <p><b>동작 정책이므로 base {@code application.yml} 이 소유한다.</b> 회수 대상 판정(만료 + 유예)은
 * {@code app.reservation.lease} 소관이고, 여기는 <b>언제 얼마나 자주 도는가</b>만 다룬다.
 *
 * <p>sweeper 는 안전망이다 — 정상 경로에서 재고를 되돌리는 주체는 Order 의 취소다. 주기를 과하게
 * 짧게 잡아도 얻는 게 없고, {@code lockAtMostFor} 를 실행 최악 시간보다 짧게 잡으면 살아있는 실행의
 * lock 이 회수돼 두 인스턴스가 같은 예약을 동시에 회수한다.
 */
@ConfigurationProperties(prefix = "app.scheduler.stock")
@Validated
@Getter
@Setter
public class StockSchedulerProperties {

    /** 만료 예약 lease 회수 잡의 실행 간격. */
    @NotNull
    private Duration leaseSweepDelay;

    /** {@code @SchedulerLock} 상한. */
    @NotNull
    private Duration lockAtMostFor;

    /** {@code @SchedulerLock} 하한. */
    @NotNull
    private Duration lockAtLeastFor;

    @AssertTrue(message = "app.scheduler.stock.lease-sweep-delay 는 0보다 커야 합니다 — "
            + "0 이면 sweeper 가 쉬지 않고 재실행되어 예약 원장을 점유합니다")
    public boolean isDelayPositive() {
        return leaseSweepDelay != null && !leaseSweepDelay.isZero() && !leaseSweepDelay.isNegative();
    }

    @AssertTrue(message = "app.scheduler.stock.lock-at-least-for 는 lock-at-most-for 보다 짧아야 합니다 — "
            + "뒤집히면 ShedLock 이 lock 을 놓을 수 없어 sweeper 가 영구히 멈춥니다")
    public boolean isLockOrderSane() {
        return lockAtLeastFor != null && lockAtMostFor != null
                && lockAtLeastFor.compareTo(lockAtMostFor) < 0;
    }
}
