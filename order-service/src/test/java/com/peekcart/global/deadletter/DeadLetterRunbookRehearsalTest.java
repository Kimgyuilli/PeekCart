package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * runbook 리허설 (계획 ④-c-2a §6 P12).
 *
 * <p><b>운영 진입점만 사용한다.</b> 직접 SQL 로 상태를 바꾸면 {@link DeadLetterRecord#discard} 의
 * "사유 필수" 가드와 전이 규칙이 우회되므로, 그런 리허설은 "SQL 이 돌았다" 만 증명하는 false-green 이다
 * (3R #7). 여기서는 runbook §4 가 지시하는 것과 <b>같은 진입점</b>({@link DeadLetterEndpoint})을 호출한다.
 *
 * <p>검증 대상은 runbook §4 의 절차 전체다: 적재({@code OPEN}) → 확인({@code ACKED}) →
 * 폐기({@code DISCARDED}), 각 단계의 DB 상태와 감사 필드.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("DLQ runbook 리허설 (OPEN → ACKED → DISCARDED)")
class DeadLetterRunbookRehearsalTest extends AbstractIntegrationTest {

    @Autowired DeadLetterRecorder recorder;
    @Autowired DeadLetterEndpoint endpoint;
    @Autowired DeadLetterRecordJpaRepository repository;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        repository.deleteAll();
    }

    @Test
    @DisplayName("runbook §4 절차대로 1건을 종결한다 — 각 단계의 DB 증적")
    void fullRehearsal() {
        // ① 적재 — OPEN
        recorder.record(origin(1, 100L));
        DeadLetterRecord seeded = repository.findAll().get(0);
        Long id = seeded.getId();
        assertThat(seeded.statusValue()).isEqualTo(DeadLetterStatus.OPEN);

        // §2.2 backlog 조회가 미결 1건을 보고한다
        assertThat(endpoint.backlog().get("unresolved")).isEqualTo(1L);

        // ② 확인 — ACKED (runbook §4.1)
        Map<String, Object> acked = endpoint.transition(id, "acknowledge", "ops-alice", null);
        assertThat(acked.get("changed")).isEqualTo(true);
        assertThat(acked.get("status")).isEqualTo("ACKED");

        DeadLetterRecord afterAck = repository.findById(id).orElseThrow();
        assertThat(afterAck.statusValue()).isEqualTo(DeadLetterStatus.ACKED);
        assertThat(afterAck.getAcknowledgedBy()).isEqualTo("ops-alice");
        assertThat(afterAck.getAcknowledgedAt()).isNotNull();
        // ACKED 는 해소가 아니다 — 여전히 미결로 집계된다
        assertThat(endpoint.backlog().get("unresolved")).isEqualTo(1L);

        // ③ 폐기 — DISCARDED (runbook §4.2)
        Map<String, Object> discarded = endpoint.transition(
                id, "discard", "ops-alice", "발행 측 스키마 버그 수정 완료, 해당 주문은 수동 취소 처리함");
        assertThat(discarded.get("changed")).isEqualTo(true);
        assertThat(discarded.get("status")).isEqualTo("DISCARDED");

        DeadLetterRecord afterDiscard = repository.findById(id).orElseThrow();
        assertThat(afterDiscard.statusValue()).isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(afterDiscard.getDiscardedBy()).isEqualTo("ops-alice");
        assertThat(afterDiscard.getDiscardedAt()).isNotNull();
        assertThat(afterDiscard.getNote()).contains("스키마 버그");

        // 종결됐으므로 미결 집계에서 빠진다
        assertThat(endpoint.backlog().get("unresolved")).isEqualTo(0L);
    }

    @Test
    @DisplayName("사유 없는 discard 는 거부된다 — 진입점이 도메인 가드를 실제로 태운다")
    void discardWithoutReasonIsRejected() {
        recorder.record(origin(2, 200L));
        Long id = repository.findAll().get(0).getId();

        Map<String, Object> result = endpoint.transition(id, "discard", "ops-alice", "   ");

        assertThat(result).containsKey("error");
        assertThat(repository.findById(id).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.OPEN);
    }

    @Test
    @DisplayName("actor 없이는 전이할 수 없다 — 누가 닫았는지 남지 않으면 감사가 불가능하다")
    void actorIsRequired() {
        recorder.record(origin(3, 300L));
        Long id = repository.findAll().get(0).getId();

        assertThat(endpoint.transition(id, "acknowledge", "  ", null)).containsKey("error");
        assertThat(repository.findById(id).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.OPEN);
    }

    @Test
    @DisplayName("재전이는 예외가 아니라 changed=false — 운영자가 두 번 눌러도 안전하다")
    void repeatedTransitionIsIdempotent() {
        recorder.record(origin(4, 400L));
        Long id = repository.findAll().get(0).getId();

        assertThat(endpoint.transition(id, "acknowledge", "ops", null).get("changed")).isEqualTo(true);
        assertThat(endpoint.transition(id, "acknowledge", "ops", null).get("changed")).isEqualTo(false);
    }

    @Test
    @DisplayName("알 수 없는 action 과 없는 id 는 오류로 응답한다")
    void rejectsUnknownActionAndMissingId() {
        recorder.record(origin(5, 500L));
        Long id = repository.findAll().get(0).getId();

        // ④-c-2b-1 이 resolve 를 실제 action 으로 만들었으므로 예시를 교체한다 — 여기서 필요한 것은
        // "매핑되지 않은 문자열이 조용히 no-op 되지 않는다" 이고, resolve 는 더 이상 그 예가 아니다.
        assertThat(endpoint.transition(id, "delete", "ops", "x")).containsKey("error");
        assertThat(endpoint.transition(999_999L, "acknowledge", "ops", null)).containsKey("error");
    }

    private DlqOrigin origin(int partition, long offset) {
        return new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "payment.completed", partition, offset,
                "order-svc-payment-completed-group", "order-1", 1_700_000_000_000L,
                "java.lang.IllegalArgumentException", "eventId 필드가 없습니다", "{}",
                null, null, null, null);
    }
}
