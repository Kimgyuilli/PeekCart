package com.peekcart.support;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeTopicsResult;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class KafkaTopicCleanupTest extends AbstractIntegrationTest {

    @Test
    void missingTopicIsTheOnlyDescribeFailureThatCanBeSkipped() throws Exception {
        KafkaFuture<TopicDescription> future = failed(new UnknownTopicOrPartitionException("missing"));
        withDescription(future, () -> assertThatCode(() -> cleanKafkaTopics("unused", "orders"))
                .doesNotThrowAnyException());
    }

    @Test
    void brokerTimeoutFailsAtCleanup() throws Exception {
        TimeoutException cause = new TimeoutException("broker unavailable");
        withDescription(failed(cause), () -> assertThatThrownBy(() -> cleanKafkaTopics("unused", "orders"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Kafka 토픽 cleanup 실패")
                .hasRootCause(cause));
    }

    @Test
    void authorizationFailureIsNotMistakenForMissingTopic() throws Exception {
        TopicAuthorizationException cause = new TopicAuthorizationException("denied");
        withDescription(failed(cause), () -> assertThatThrownBy(() -> cleanKafkaTopics("unused", "orders"))
                .isInstanceOf(IllegalStateException.class).hasRootCause(cause));
    }

    @Test
    void interruptionFailsCleanupAndRestoresInterruptFlag() throws Exception {
        @SuppressWarnings("unchecked")
        KafkaFuture<TopicDescription> future = mock(KafkaFuture.class);
        when(future.get()).thenThrow(new InterruptedException("cancelled"));
        try {
            withDescription(future, () -> {
                assertThatThrownBy(() -> cleanKafkaTopics("unused", "orders"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasCauseInstanceOf(InterruptedException.class);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            });
        } finally {
            Thread.interrupted();
        }
    }

    private KafkaFuture<TopicDescription> failed(Throwable cause) throws Exception {
        @SuppressWarnings("unchecked")
        KafkaFuture<TopicDescription> future = mock(KafkaFuture.class);
        when(future.get()).thenThrow(new ExecutionException(cause));
        return future;
    }

    private void withDescription(KafkaFuture<TopicDescription> future, Runnable assertion) {
        Admin admin = mock(Admin.class);
        DescribeTopicsResult descriptions = mock(DescribeTopicsResult.class);
        when(admin.describeTopics(List.of("orders"))).thenReturn(descriptions);
        when(descriptions.topicNameValues()).thenReturn(Map.of("orders", future));
        try (MockedStatic<Admin> factory = mockStatic(Admin.class)) {
            factory.when(() -> Admin.create(Map.of("bootstrap.servers", "unused"))).thenReturn(admin);
            assertion.run();
            verify(admin, never()).listOffsets(anyMap());
            verify(admin, never()).deleteRecords(anyMap());
            verify(admin).close();
        }
    }
}
