package com.peekcart.order.infrastructure.scheduler;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Order 타임아웃 잡의 주기·lock 정책 (ADR-0007).
 *
 * <p><b>동작 정책이므로 base {@code application.yml} 이 소유한다</b> — 환경마다 달라지는 연결 정보가
 * 아니다. 주기가 환경별로 갈리면 "운영에서만 재고가 늦게 풀린다" 같은 재현 불가 문제가 생긴다.
 *
 * <p>이 클래스가 존재하는 이유는 두 가지다. 첫째, {@code @Scheduled} 의 placeholder 는 문자열이라
 * 오타·음수·단위 착각이 <b>기동 시점에 걸러지지 않는다</b>. 둘째, lock 불변식은 값 하나로는 표현되지
 * 않는다 — 아래 {@code @AssertTrue} 들이 그 관계를 부팅 시 강제한다.
 *
 * <p><b>lock 불변식</b>:
 * <ul>
 *   <li>{@code lockAtMostFor} &gt; 1회 실행 최악 시간 — 짧으면 살아있는 실행의 lock 이 회수돼
 *       다른 인스턴스가 같은 주문을 동시에 취소한다</li>
 *   <li>{@code lockAtLeastFor} &lt; {@code lockAtMostFor} — 뒤집히면 lock 이 영구히 잡힌다</li>
 *   <li>{@code delay} &gt; 0 — {@code fixedDelay} 가 0 이면 잡이 CPU 를 점유한다</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "app.scheduler.order")
@Validated
@Getter
@Setter
public class OrderSchedulerProperties {

    /** 결제 대기 상한 초과 주문 취소 잡의 실행 간격. */
    @NotNull
    private Duration cancelExpiredDelay;

    /** 예약 확정·결제 미시작 PENDING 주문 취소 잡의 실행 간격. */
    @NotNull
    private Duration unconfirmedReservationDelay;

    /** 예약 lease 만료 주문 취소 잡의 실행 간격. */
    @NotNull
    private Duration leaseExpiryDelay;

    /** 세 잡 공통 {@code @SchedulerLock} 상한. */
    @NotNull
    private Duration lockAtMostFor;

    /** 세 잡 공통 {@code @SchedulerLock} 하한. 짧은 잡이 lock 을 즉시 놓고 재발화하는 것을 막는다. */
    @NotNull
    private Duration lockAtLeastFor;

    @AssertTrue(message = "app.scheduler.order 의 세 delay 는 모두 0보다 커야 합니다 — "
            + "0 이면 fixedDelay 잡이 쉬지 않고 재실행되어 DB 를 점유합니다")
    public boolean isDelaysPositive() {
        return positive(cancelExpiredDelay) && positive(unconfirmedReservationDelay)
                && positive(leaseExpiryDelay);
    }

    @AssertTrue(message = "app.scheduler.order.lock-at-least-for 는 lock-at-most-for 보다 짧아야 합니다 — "
            + "뒤집히면 ShedLock 이 lock 을 놓을 수 없어 잡이 영구히 멈춥니다")
    public boolean isLockOrderSane() {
        return lockAtLeastFor != null && lockAtMostFor != null
                && lockAtLeastFor.compareTo(lockAtMostFor) < 0;
    }

    private static boolean positive(Duration d) {
        return d != null && !d.isZero() && !d.isNegative();
    }
}
