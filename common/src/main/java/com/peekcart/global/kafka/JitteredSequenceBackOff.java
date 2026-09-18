package com.peekcart.global.kafka;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link FixedSequenceBackOff} 와 같은 고정 간격 시퀀스이되, 각 간격에 <b>무작위 편차(jitter)</b>를 더한다.
 *
 * <p><b>왜 필요한가.</b> jitter 가 없으면 동시에 실패한 소비자들이 <b>똑같은 간격</b>을 기다렸다가
 * <b>같은 순간에 함께</b> 재시도한다. 그래서 재시도가 경합을 흩뜨리지 않고 그대로 보존한다 —
 * 횟수를 늘려도 같은 충돌을 반복할 뿐이다. D-025 측정에서 낙관락 충돌 7건이 재시도 4회를 전부
 * 소진하고 DLQ 로 간 것이 이 형태였다 (ADR-0025 D2).
 *
 * <p>간격은 {@code interval × [1-ratio, 1+ratio]} 범위에서 균등하게 뽑는다. 단계별 증가 형태
 * (1s → 5s → 30s 같은)는 유지한 채 <b>같은 단계 안에서만</b> 흩는다.
 */
public class JitteredSequenceBackOff implements BackOff {

    private final double jitterRatio;
    private final long[] intervals;

    /**
     * @param jitterRatio 편차 비율. {@code 0.0} 이상 {@code 1.0} 미만 (예: {@code 0.5} = ±50%)
     * @param intervals   단계별 기준 간격(ms). 소진하면 재시도를 중단한다
     */
    public JitteredSequenceBackOff(double jitterRatio, long... intervals) {
        if (jitterRatio < 0.0 || jitterRatio >= 1.0) {
            throw new IllegalArgumentException("jitterRatio 는 [0.0, 1.0) 이어야 합니다: " + jitterRatio);
        }
        this.jitterRatio = jitterRatio;
        this.intervals = intervals.clone();
    }

    @Override
    public BackOffExecution start() {
        return new BackOffExecution() {
            private int index = 0;

            @Override
            public long nextBackOff() {
                if (index >= intervals.length) {
                    return STOP;
                }
                return jitter(intervals[index++]);
            }
        };
    }

    private long jitter(long interval) {
        if (jitterRatio == 0.0 || interval <= 0) {
            return interval;
        }
        double spread = interval * jitterRatio;
        return Math.round(ThreadLocalRandom.current().nextDouble(interval - spread, interval + spread));
    }
}
