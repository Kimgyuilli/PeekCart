package com.peekcart.support;

import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.MessageListenerContainer;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ListenerAssignmentTest extends AbstractIntegrationTest {

    @Test
    void emptySelectionIsNotSuccessfulAssignment() {
        assertThatThrownBy(() -> awaitListenerAssignment(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("리스너가 없다");
    }

    @Test
    void runningAloneIsNotEnoughAndAssignmentIsPolled() {
        MessageListenerContainer listener = mock(MessageListenerContainer.class);
        when(listener.isRunning()).thenReturn(true);
        when(listener.getListenerId()).thenReturn("dlq-test");
        when(listener.getAssignedPartitions()).thenReturn(
                null, List.of(), List.of(new TopicPartition("test.dlq", 0)));

        awaitListenerAssignment(List.of(listener));

        verify(listener, atLeast(3)).getAssignedPartitions();
    }
}
