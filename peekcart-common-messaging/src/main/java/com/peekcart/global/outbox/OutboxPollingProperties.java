package com.peekcart.global.outbox;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * outbox poller 의 주기와 ShedLock 최소 보존 (ADR-0045).
 *
 * <p><b>동작 정책이므로 서비스 base {@code application.yml} 이 소유한다</b>(ADR-0007). 값을 주입하려고 있는
 * 클래스가 아니다. {@link OutboxPollingScheduler} 의 두 애노테이션이 같은 키를 placeholder 로 읽는데,
 * ShedLock 은 lock 설정을 <b>실행마다</b> 애노테이션에서 만든다. 그래서 잘못된 값은 부팅이 아니라 매 실행의
 * 예외로만 드러난다. 그 검사를 부팅 시점으로 당기는 것이 이 클래스의 역할이다.
 *
 * <p><b>불변식</b>:
 * <ul>
 *   <li>{@code delay} &gt; 0 — 0 이면 poller 가 쉬지 않고 DB 를 두드린다</li>
 *   <li>0 &le; {@code lockAtLeastFor} &lt; {@code delay} — 최소 보존이 주기 이상이면 같은 인스턴스의 다음 실행이
 *       자기 lock 에 막혀 실행 주기가 최소 보존으로 늘어난다(D-075 실측: 주기 500ms 에 보존 4s 면 매번 skip)</li>
 *   <li>{@code lockAtLeastFor} &lt; {@link OutboxPollingScheduler#LOCK_AT_MOST_FOR} — 뒤집히면 ShedLock 이 실행마다
 *       예외를 던져 발행이 멈춘다</li>
 * </ul>
 *
 * <p>최소 보존은 정합성과 무관하다. lock 시각이 DB 시각이라 노드 간 시계 차이를 보정할 일이 없고, 실행 중에는
 * lockAtMostFor 가 다른 인스턴스를 막는다. 남는 역할은 여러 인스턴스의 실행을 그 간격에 한 번으로 묶는 것이다.
 */
@ConfigurationProperties(prefix = "app.outbox.polling")
@Validated
@Getter
@Setter
public class OutboxPollingProperties {

    /** poller 실행 간격({@code fixedDelay}). 앞 실행이 끝난 뒤부터 잰다. */
    @NotNull
    private Duration delay;

    /** {@code @SchedulerLock} 최소 보존. 실행이 이보다 짧게 끝나도 lock 을 이 시간까지 쥔다. */
    @NotNull
    private Duration lockAtLeastFor;

    @AssertTrue(message = "app.outbox.polling.delay 는 0보다 커야 합니다 — "
            + "0 이면 poller 가 쉬지 않고 재실행되어 DB 를 점유합니다")
    public boolean isDelayPositive() {
        return delay != null && !delay.isZero() && !delay.isNegative();
    }

    @AssertTrue(message = "app.outbox.polling.lock-at-least-for 는 0 이상이고 delay 보다 짧아야 합니다 — "
            + "delay 이상이면 같은 인스턴스의 다음 실행이 자기 lock 에 막혀 주기가 최소 보존으로 늘어납니다")
    public boolean isLockAtLeastForWithinDelay() {
        return lockAtLeastFor != null && delay != null
                && !lockAtLeastFor.isNegative() && lockAtLeastFor.compareTo(delay) < 0;
    }

    @AssertTrue(message = "app.outbox.polling.lock-at-least-for 는 lockAtMostFor(5분)보다 짧아야 합니다 — "
            + "뒤집히면 ShedLock 이 실행마다 예외를 던져 발행이 멈춥니다")
    public boolean isLockAtLeastForBelowLockAtMostFor() {
        return lockAtLeastFor != null
                && lockAtLeastFor.compareTo(Duration.parse(OutboxPollingScheduler.LOCK_AT_MOST_FOR)) < 0;
    }
}
