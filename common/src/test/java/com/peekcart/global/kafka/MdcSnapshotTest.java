package com.peekcart.global.kafka;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MdcSnapshot 단위 테스트")
class MdcSnapshotTest {

    @BeforeEach
    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("MDC 미설정 시 traceId / userId 둘 다 null")
    void noMdcReturnsNulls() {
        MdcSnapshot.Snapshot snap = MdcSnapshot.current();

        assertThat(snap.traceId()).isNull();
        assertThat(snap.userId()).isNull();
    }

    @Test
    @DisplayName("traceId 만 설정된 상태")
    void onlyTraceIdSet() {
        MDC.put("traceId", "trace-001");

        MdcSnapshot.Snapshot snap = MdcSnapshot.current();

        assertThat(snap.traceId()).isEqualTo("trace-001");
        assertThat(snap.userId()).isNull();
    }

    @Test
    @DisplayName("traceId / userId 둘 다 설정된 상태")
    void bothSet() {
        MDC.put("traceId", "trace-001");
        MDC.put("userId", "42");

        MdcSnapshot.Snapshot snap = MdcSnapshot.current();

        assertThat(snap.traceId()).isEqualTo("trace-001");
        assertThat(snap.userId()).isEqualTo("42");
    }
}
