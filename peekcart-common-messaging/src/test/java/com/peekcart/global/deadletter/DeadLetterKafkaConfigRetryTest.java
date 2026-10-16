package com.peekcart.global.deadletter;

import com.peekcart.support.KafkaRetryContract;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DeadLetterKafkaConfigRetryTest {
    @Test
    void exhaustionHandsOffWithoutAcknowledgingOrPublishingAnotherDlq() {
        DeadLetterContainerGuard guard = mock(DeadLetterContainerGuard.class);
        var handler = new DeadLetterKafkaConfig(guard).deadLetterErrorHandler();
        assertThat(handler.isAckAfterHandle()).isFalse();
        KafkaRetryContract.assertThreeJitteredRetriesThenRecovery(handler,
                () -> verifyNoInteractions(guard),
                () -> verify(guard, times(1)).onRetriesExhausted(any(ConsumerRecord.class), any(Exception.class)));
        assertThat(handler.isAckAfterHandle()).isFalse();
    }
}
