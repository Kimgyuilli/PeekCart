package com.peekcart.payment.infrastructure.kafka;

import com.peekcart.global.port.SlackPort;
import com.peekcart.support.KafkaRetryContract;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentKafkaConfigRetryTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void actualHandlerRetriesThreeTimesWithJitterThenPublishesOnceToDlq() {
        KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
        SlackPort slack = mock(SlackPort.class);
        var handler = new PaymentKafkaConfig(slack, new com.peekcart.global.retention.IdempotencyRetentionProperties()).kafkaErrorHandler(kafka);

        KafkaRetryContract.assertThreeJitteredRetriesThenRecovery(handler,
                () -> verify(kafka, never()).send(any(ProducerRecord.class)),
                () -> {
                    ArgumentCaptor<ProducerRecord> sent = ArgumentCaptor.forClass(ProducerRecord.class);
                    verify(kafka, times(1)).send(sent.capture());
                    assertThat(sent.getValue().topic()).isEqualTo("retry.contract.dlq");
                    assertThat(sent.getValue().key()).isEqualTo("key");
                    assertThat(sent.getValue().value()).isEqualTo("payload");
                    verify(slack, times(1)).send(any(String.class));
                });
    }
}
