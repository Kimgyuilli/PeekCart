package com.peekcart.global.kafka;

import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

/**
 * 고정된 간격 배열을 순서대로 반환하는 BackOff 구현체.
 * 배열을 모두 소진하면 {@link BackOffExecution#STOP}을 반환하여 재시도를 중단한다.
 */
public class FixedSequenceBackOff implements BackOff {

    private final long[] intervals;

    public FixedSequenceBackOff(long... intervals) {
        this.intervals = intervals;
    }

    @Override
    public BackOffExecution start() {
        return new BackOffExecution() {
            private int index = 0;

            @Override
            public long nextBackOff() {
                if (index < intervals.length) {
                    return intervals[index++];
                }
                return STOP;
            }
        };
    }
}
