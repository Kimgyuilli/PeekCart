package com.peekcart.support;

import com.peekcart.global.kafka.JitteredSequenceBackOff;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.BackOffHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Exercises the actual configured handler; only the wall-clock sleeper is replaced. */
public final class KafkaRetryContract {
    private KafkaRetryContract() { }

    public static void assertThreeJitteredRetriesThenRecovery(CommonErrorHandler handler,
                                                             Runnable assertNotRecovered,
                                                             Runnable assertRecovered) {
        Object tracker = ReflectionTestUtils.getField(handler, "failureTracker");
        BackOff backOff = (BackOff) ReflectionTestUtils.getField(tracker, "backOff");
        assertThat(backOff).isInstanceOf(JitteredSequenceBackOff.class);
        assertThat(ReflectionTestUtils.getField(backOff, "jitterRatio")).isEqualTo(0.5);
        BackOffExecution execution = backOff.start();
        assertThat(execution.nextBackOff()).isBetween(500L, 1_500L);
        assertThat(execution.nextBackOff()).isBetween(2_500L, 7_500L);
        assertThat(execution.nextBackOff()).isBetween(15_000L, 45_000L);
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);

        List<Long> delays = new ArrayList<>();
        ReflectionTestUtils.setField(tracker, "backOffHandler", new BackOffHandler() {
            @Override
            public void onNextBackOff(MessageListenerContainer container, Exception exception, long delay) {
                delays.add(delay);
            }
        });
        ConsumerRecord<String, String> record = new ConsumerRecord<>("retry.contract", 0, 17L, "key", "payload");
        IllegalStateException failure = new IllegalStateException("transient test failure");
        Consumer<?, ?> consumer = mock(Consumer.class);
        MessageListenerContainer container = mock(MessageListenerContainer.class);
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                assertThat(handler.handleOne(failure, record, consumer, container)).isFalse();
                assertNotRecovered.run();
            }
            assertThat(handler.handleOne(failure, record, consumer, container)).isTrue();
            assertRecovered.run();
            assertThat(delays).hasSize(3);
            assertThat(delays.get(0)).isBetween(500L, 1_500L);
            assertThat(delays.get(1)).isBetween(2_500L, 7_500L);
            assertThat(delays.get(2)).isBetween(15_000L, 45_000L);
        } finally {
            handler.clearThreadState();
        }
    }
}
