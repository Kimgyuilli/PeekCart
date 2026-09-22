package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLQ 원장 메트릭 (계획 ④-d-1 §5 P3).
 *
 * <p><b>{@link DeadLetterEndpoint} 와 값이 일치하는지</b>를 함께 본다. 두 표면이 서로 다른 방식으로
 * 세면 값이 갈라졌을 때 어느 쪽이 맞는지 알 수 없고, 그러면 둘 다 신뢰할 수 없게 된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("DLQ 원장 메트릭 통합 테스트")
class DeadLetterMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired DeadLetterRecorder recorder;
    @Autowired DeadLetterEndpoint endpoint;
    @Autowired DeadLetterRecordJpaRepository repository;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        repository.deleteAll();
    }

    @Test
    @DisplayName("미결 1건이면 dlq.backlog=1, 종결하면 0 — 누적이 아니라 잔량이다")
    void backlogTracksUnresolved() {
        assertThat(gauge("dlq.backlog")).isZero();

        recorder.record(origin(1, 100L));
        assertThat(gauge("dlq.backlog")).isEqualTo(1.0);

        DeadLetterRecord record = repository.findAll().get(0);
        endpoint.transition(record.getId(), "discard", "ops", "재처리 불필요 — 상류 버그 수정됨");

        assertThat(gauge("dlq.backlog")).isZero();
    }

    @Test
    @DisplayName("ACKED 는 여전히 미결로 집계된다 — 확인은 해소가 아니다")
    void ackedStillCountsAsBacklog() {
        recorder.record(origin(2, 200L));
        DeadLetterRecord record = repository.findAll().get(0);

        endpoint.transition(record.getId(), "acknowledge", "ops", null);

        assertThat(gauge("dlq.backlog")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("메트릭과 actuator 조회 표면의 값이 일치한다 — 같은 쿼리를 쓰는지 확인")
    void metricMatchesActuator() {
        recorder.record(origin(3, 300L));
        recorder.record(origin(4, 400L));

        Object unresolved = endpoint.backlog().get("unresolved");

        assertThat(gauge("dlq.backlog")).isEqualTo(((Number) unresolved).doubleValue());
        assertThat(gauge("dlq.backlog")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("미결 0건이면 dlq.oldest.age 는 0 — null 이 아니라 0 이어야 alert 식이 성립한다")
    void oldestAgeIsZeroWhenEmpty() {
        assertThat(gauge("dlq.oldest.age")).isZero();
    }

    @Test
    @DisplayName("미결이 있으면 dlq.oldest.age 가 0 이상 — 가장 오래된 건 기준")
    void oldestAgeTracksOldest() {
        recorder.record(origin(5, 500L));

        assertThat(gauge("dlq.oldest.age")).isGreaterThanOrEqualTo(0.0);
        assertThat(gauge("dlq.backlog")).isEqualTo(1.0);
    }

    // ---------- helpers ----------

    private DlqOrigin origin(int partition, long offset) {
        return new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "payment.completed", partition, offset,
                "order-svc-payment-completed-group", "order-1", 1_700_000_000_000L,
                "java.lang.IllegalStateException", "boom", "{}",
                null, null, null, null);
    }

    private double gauge(String name) {
        var g = meterRegistry.find(name).gauge();
        return g == null ? -1.0 : g.value();
    }
}
