package com.peekcart.global.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.backoff.BackOffExecution;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FixedSequenceBackOff 단위 테스트")
class FixedSequenceBackOffTest {

    @Test
    @DisplayName("지정된 간격을 순서대로 반환한 후 STOP을 반환한다")
    void returnsIntervalsInOrderThenStop() {
        FixedSequenceBackOff backOff = new FixedSequenceBackOff(1_000, 5_000, 30_000);
        BackOffExecution execution = backOff.start();

        assertThat(execution.nextBackOff()).isEqualTo(1_000);
        assertThat(execution.nextBackOff()).isEqualTo(5_000);
        assertThat(execution.nextBackOff()).isEqualTo(30_000);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    @DisplayName("빈 배열이면 첫 호출에서 STOP을 반환한다")
    void emptyIntervalsReturnsStopImmediately() {
        FixedSequenceBackOff backOff = new FixedSequenceBackOff();
        BackOffExecution execution = backOff.start();

        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    @DisplayName("start()는 독립적인 BackOffExecution 인스턴스를 반환한다")
    void startReturnsIndependentExecutions() {
        FixedSequenceBackOff backOff = new FixedSequenceBackOff(1_000, 5_000);

        BackOffExecution exec1 = backOff.start();
        BackOffExecution exec2 = backOff.start();

        exec1.nextBackOff(); // 1000 소비
        assertThat(exec2.nextBackOff()).isEqualTo(1_000); // exec2는 독립적
    }
}
