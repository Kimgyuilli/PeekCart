package com.peekcart.global.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JitteredSequenceBackOff 단위 테스트")
class JitteredSequenceBackOffTest {

    private static final long[] INTERVALS = {1_000, 5_000, 30_000};

    @Test
    @DisplayName("단계 수는 그대로이고, 소진하면 STOP 을 반환한다")
    void yieldsOneValuePerStepThenStops() {
        BackOffExecution execution = new JitteredSequenceBackOff(0.5, INTERVALS).start();

        assertThat(drain(execution)).hasSize(INTERVALS.length);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    @DisplayName("각 간격은 기준값의 ±ratio 범위 안에 있다 — 단계별 증가 형태는 유지된다")
    void staysWithinRatioBounds() {
        JitteredSequenceBackOff backOff = new JitteredSequenceBackOff(0.5, INTERVALS);

        for (int trial = 0; trial < 200; trial++) {
            List<Long> drawn = drain(backOff.start());
            for (int step = 0; step < INTERVALS.length; step++) {
                assertThat(drawn.get(step))
                        .as("step %d 간격", step)
                        .isBetween((long) (INTERVALS[step] * 0.5), (long) (INTERVALS[step] * 1.5));
            }
        }
    }

    /**
     * 핵심 성질 — 동시에 실패한 소비자들이 <b>서로 다른</b> 시각에 깨어나야 재시도가 경합을 푼다.
     * jitter 가 없으면 같은 순간에 함께 깨어나 같은 충돌을 반복한다(D-025, ADR-0025 D2).
     */
    @Test
    @DisplayName("서로 다른 실행은 서로 다른 일정을 낸다 — lockstep 이 깨진다")
    void independentExecutionsDoNotMoveInLockstep() {
        JitteredSequenceBackOff backOff = new JitteredSequenceBackOff(0.5, INTERVALS);

        Set<Long> firstStepValues = new HashSet<>();
        for (int consumer = 0; consumer < 50; consumer++) {
            firstStepValues.add(backOff.start().nextBackOff());
        }

        assertThat(firstStepValues)
                .as("50개 소비자의 첫 재시도 시각이 한 값으로 뭉치면 jitter 가 없는 것이다")
                .hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("ratio 0 이면 기준값 그대로 — jitter 를 끌 수 있다")
    void zeroRatioYieldsExactIntervals() {
        assertThat(drain(new JitteredSequenceBackOff(0.0, INTERVALS).start()))
                .containsExactly(1_000L, 5_000L, 30_000L);
    }

    @Test
    @DisplayName("ratio 가 [0.0, 1.0) 밖이면 생성 시점에 거부한다")
    void rejectsOutOfRangeRatio() {
        assertThatThrownBy(() -> new JitteredSequenceBackOff(1.0, INTERVALS))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JitteredSequenceBackOff(-0.1, INTERVALS))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static List<Long> drain(BackOffExecution execution) {
        List<Long> intervals = new ArrayList<>();
        for (long next = execution.nextBackOff(); next != BackOffExecution.STOP; next = execution.nextBackOff()) {
            intervals.add(next);
        }
        return intervals;
    }
}
