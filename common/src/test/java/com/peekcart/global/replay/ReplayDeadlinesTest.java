package com.peekcart.global.replay;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V-10 · V-13b — 멱등 안전창 계산 (ADR-0020 §D5-3).
 *
 * <p>기대값을 ADR 본문 값으로 <b>리터럴 독립 기재</b>한다(N17) — 구현 상수를 참조하면 상수를 고쳤을 때
 * 테스트가 같이 따라가 아무 것도 검사하지 않는다.
 */
class ReplayDeadlinesTest {

    private static final Duration SKEW = Duration.ofMinutes(5);
    private static final Duration WINDOW = Duration.ofDays(7);

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 11, 12, 0, 0);

    private static long millis(LocalDateTime at) {
        return at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Test
    @DisplayName("과거 timestamp — deadline = original + window")
    void pastTimestamp() {
        LocalDateTime original = NOW.minusHours(3);

        ReplayDeadlines.Result result = ReplayDeadlines.compute(millis(original), NOW, SKEW, WINDOW);

        assertThat(result.rejected()).isFalse();
        assertThat(result.deadline()).isEqualTo(original.plusDays(7));
    }

    @Test
    @DisplayName("허용 범위 안의 미래(+4분) — now 로 clamp 한다. 창을 4분 늘려주지 않는다")
    void futureWithinSkewIsClamped() {
        ReplayDeadlines.Result result =
                ReplayDeadlines.compute(millis(NOW.plusMinutes(4)), NOW, SKEW, WINDOW);

        assertThat(result.rejected()).isFalse();
        // clamp 를 빼면 NOW+4분+7일 이 되어 red — 안전창이 부당하게 연장된 것이다.
        assertThat(result.deadline()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    @DisplayName("허용 범위를 넘는 미래(+6분) — 거부한다")
    void futureBeyondSkewIsRejected() {
        ReplayDeadlines.Result result =
                ReplayDeadlines.compute(millis(NOW.plusMinutes(6)), NOW, SKEW, WINDOW);

        assertThat(result.rejected()).isTrue();
        assertThat(result.deadline()).isNull();
    }

    @Test
    @DisplayName("경계: 정확히 +5분(= skew budget)은 허용되고 clamp 된다")
    void exactlySkewBudgetIsAllowed() {
        ReplayDeadlines.Result result =
                ReplayDeadlines.compute(millis(NOW.plusMinutes(5)), NOW, SKEW, WINDOW);

        assertThat(result.rejected()).isFalse();
        assertThat(result.deadline()).isEqualTo(NOW.plusDays(7));
    }

    @Test
    @DisplayName("만료 경계 3종 — deadline-1ms 는 허용, deadline 과 deadline+1ms 는 만료")
    void expiryBoundary() {
        LocalDateTime deadline = NOW.plusDays(7);

        assertThat(ReplayDeadlines.expired(deadline, deadline.minusNanos(1_000_000))).isFalse();
        // 경계값 자체가 만료다 — ADR 은 `now < replay_deadline` 을 요구한다.
        assertThat(ReplayDeadlines.expired(deadline, deadline)).isTrue();
        assertThat(ReplayDeadlines.expired(deadline, deadline.plusNanos(1_000_000))).isTrue();
    }
}
