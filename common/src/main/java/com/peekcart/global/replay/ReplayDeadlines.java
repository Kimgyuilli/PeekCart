package com.peekcart.global.replay;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 멱등 안전창 {@code replay_deadline} 계산 (ADR-0020 §D5-3 · 구현 ④-c-2b-4a P19).
 *
 * <p><b>drain 판정과 무관하다.</b> 롤백 drain 의 기준시각은 {@code last_replay_settled_at} 이며,
 * 이 값을 그 용도로 겸용하려던 초안은 계획 리뷰에서 반증됐다 — 7일 안전창이 초 단위로 축소된다.
 *
 * <p><b>미래 timestamp 는 {@code clockSkewBudget} 까지만 허용하고, 허용분은 {@code now} 로 clamp 한다.</b>
 * clamp 하지 않으면 4분 미래인 timestamp 가 <b>안전창을 4분 연장</b>한다 — ADR 은 "허용된 미래값은
 * {@code now} 로 clamp 해 계산한다(창을 늘려주지 않는다)" 로 명시돼 있다.
 */
public final class ReplayDeadlines {

    /**
     * @param deadline  계산된 안전창 종료 시각. {@code rejection} 이 있으면 {@code null}
     * @param rejection 거부 사유. 통과면 {@code null}
     */
    public record Result(LocalDateTime deadline, String rejection) {

        public boolean rejected() {
            return rejection != null;
        }
    }

    private ReplayDeadlines() {
    }

    /**
     * 원본 레코드 timestamp(epoch millis)로부터 안전창을 계산한다.
     *
     * <p>{@code originalTimestamp} 가 {@code null} 인 경우는 여기서 다루지 않는다 —
     * 그것은 ADR §D5-2 <b>축 6</b>의 독립 금지 사유이고, 축을 합치면 운영자가 한 번에 한 축만 본다.
     */
    public static Result compute(long originalTimestampMillis, LocalDateTime now,
                                 Duration clockSkewBudget, Duration replayWindow) {
        LocalDateTime original = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(originalTimestampMillis), ZoneId.systemDefault());

        if (original.isAfter(now.plus(clockSkewBudget))) {
            return new Result(null, "원본 timestamp 가 허용 시계 오차(" + clockSkewBudget
                    + ")를 넘는 미래값이다: " + original);
        }

        // 허용된 미래값은 now 로 clamp — 창을 늘려주지 않는다.
        LocalDateTime base = original.isAfter(now) ? now : original;
        return new Result(base.plus(replayWindow), null);
    }

    /** {@code now < replay_deadline} 강제 (ADR §D5-3). 경계값({@code now == deadline})은 만료다. */
    public static boolean expired(LocalDateTime deadline, LocalDateTime now) {
        return !now.isBefore(deadline);
    }
}
