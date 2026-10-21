package com.peekcart.global.deadletter;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.support.KeyHolder;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class DeadLetterIdAllocatorTest {
    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0, -1, Long.MAX_VALUE})
    void invalidOrExhaustedGeneratedKeyFailsBeforeLedgerUse(Long generated) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        doAnswer(call -> {
            KeyHolder keys = call.getArgument(1);
            if (generated != null) keys.getKeyList().add(Map.of("id", generated));
            return 1;
        }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));

        assertThatThrownBy(() -> new DeadLetterIdAllocator(jdbc).allocate())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("범위");
        verify(jdbc, never()).update(eq("DELETE FROM dead_letter_record_ids WHERE id = ?"), anyLong());
    }
}
