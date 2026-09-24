package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DLQ 원장 적재 통합 테스트 (계획 ④-c-2a §6 P1·P3·P4·P6·P7).
 *
 * <p><b>실제 Kafka 왕복으로 검증한다.</b> recorder 를 직접 호출하면 group/factory/토픽이 틀려도
 * 통과한다 — ④-c-1b 에서 확인된 false-green 유형이라 여기서는 브로커를 거친다.
 *
 * <p>다만 "4서비스 중 1곳만 적재" 라는 <b>전역</b> 불변식은 여기서 증명하지 않는다(cross-service,
 * ④-d 부모 P12). 본 테스트는 order-service 의 행동을, {@code DlqTopologyContractTest} 가 매핑의
 * 무모순을 각각 맡는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // ADR-0029: 리스너는 테스트에서 기본 off 다. 실제 Kafka 왕복으로 DLQ listener 배선을 고정하는 것이 검증 대상이다.
        "app.kafka.listener.enabled=true"
})
// 켠 자율 writer 의 수명을 자기 클래스에 가둔다 (ADR-0029 D3) — context 가 캐시된 채 남으면
// 리스너가 이후 클래스의 공유 브로커·DB 를 계속 고친다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("DLQ 원장 적재 통합 테스트")
class DeadLetterLedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired DeadLetterRecordJpaRepository repository;
    @Autowired DeadLetterRecorder recorder;

    private static final String OWNED_GROUP = "order-svc-payment-completed-group";
    private static final String FOREIGN_GROUP = "product-svc-payment-completed-group";

    @BeforeEach
    void setUp() {
        cleanDatabase();
        repository.deleteAll();
    }

    // ---------- 실제 Kafka 왕복 ----------

    @Test
    @DisplayName("자기 group 실패분이 .dlq 로 오면 원장 1행이 남는다 (실제 Kafka 왕복 = listener 배선 고정)")
    void ownedGroupIsRecorded() {
        send("payment.completed.dlq", "payment.completed", 1, 42L, OWNED_GROUP, "{\"eventId\":\"evt-1\"}");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findAll()).hasSize(1));

        DeadLetterRecord record = repository.findAll().get(0);
        assertThat(record.getOriginKind()).isEqualTo(DlqOriginKind.RESOLVED_ORIGIN);
        assertThat(record.getOriginTopic()).isEqualTo("payment.completed");
        assertThat(record.getOriginPartition()).isEqualTo(1);
        assertThat(record.getOriginOffset()).isEqualTo(42L);
        assertThat(record.getFailedConsumerGroup()).isEqualTo(OWNED_GROUP);
        assertThat(record.getEventId()).isEqualTo("evt-1");
        assertThat(record.statusValue()).isEqualTo(DeadLetterStatus.OPEN);
        assertThat(record.getAttemptCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("남의 group 실패분은 적재하지 않는다 — 음성 대조 (공유 DLQ 에서 중복 기록 0)")
    void foreignGroupIsSkipped() {
        send("payment.completed.dlq", "payment.completed", 1, 43L, FOREIGN_GROUP, "{\"eventId\":\"evt-2\"}");
        // 대조군: 같은 토픽에 자기 group 도 보내 listener 가 살아있음을 증명한다.
        send("payment.completed.dlq", "payment.completed", 1, 44L, OWNED_GROUP, "{\"eventId\":\"evt-3\"}");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findAll()).hasSize(1));

        assertThat(repository.findAll().get(0).getFailedConsumerGroup()).isEqualTo(OWNED_GROUP);
    }

    @Test
    @DisplayName("group 헤더가 없으면 quarantine listener 가 자기 발행 토픽에서만 적재한다")
    void quarantineRecordsGrouplessOnOwnPublishedTopic() {
        // order 가 발행하는 토픽 → order 가 quarantine 소유자
        sendWithoutGroup("order.created.dlq", "order.created", 0, 7L, "{\"eventId\":\"evt-4\"}");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findAll()).hasSize(1));

        DeadLetterRecord record = repository.findAll().get(0);
        assertThat(record.getFailedConsumerGroup()).isEqualTo(DlqOrigin.UNKNOWN_CONSUMER_GROUP);
        assertThat(record.getOriginTopic()).isEqualTo("order.created");
    }

    @Test
    @DisplayName("group 이 판독된 레코드는 quarantine listener 가 건드리지 않는다 — 이중 적재 0")
    void quarantineSkipsResolvedGroup() {
        // order.created.dlq 는 order 의 quarantine 대상이지만 group 이 있으면 대상이 아니다.
        // order 는 order.created 를 소비하지 않으므로 소비 경로 소유자도 아니다 → 어느 쪽도 적재 안 함.
        send("order.created.dlq", "order.created", 0, 8L, "product-svc-order-created-group", "{}");
        // 대조군
        sendWithoutGroup("order.created.dlq", "order.created", 0, 9L, "{}");

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findAll()).hasSize(1));

        assertThat(repository.findAll().get(0).getOriginOffset()).isEqualTo(9L);
    }

    // ---------- 멱등 · malformed (recorder 직접 호출로 빠르게 전수) ----------

    @Test
    @DisplayName("같은 좌표가 2회 유입되면 1행 + attemptCount 2 — 새 행을 만들지 않는다")
    void duplicateIntakeKeepsSingleRow() {
        DlqOrigin origin = resolvedOrigin(1, 100L, OWNED_GROUP);

        assertThat(recorder.record(origin)).isTrue();
        assertThat(recorder.record(origin)).isFalse();

        List<DeadLetterRecord> all = repository.findAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getAttemptCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("eventId 없는 메시지도 원장에 남는다 — eventId 는 식별자가 아니라 보조 검색키다")
    void recordsMessageWithoutEventId() {
        DlqOrigin origin = new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "payment.completed", 2, 200L,
                OWNED_GROUP, "k", null, "java.lang.IllegalArgumentException",
                "eventId 필드가 없습니다", "invalid-json-message",
                null, null, null, null);

        assertThat(recorder.record(origin)).isTrue();

        DeadLetterRecord record = repository.findAll().get(0);
        assertThat(record.getEventId()).isNull();
        assertThat(record.getPayload()).isEqualTo("invalid-json-message");
    }

    @Test
    @DisplayName("origin 헤더 판독 불가 → DLQ 자신의 좌표로 적재되고 6컬럼 어디에도 NULL 이 없다")
    void recordsDlqOriginWithoutNulls() {
        DlqOrigin origin = new DlqOrigin(DlqOriginKind.DLQ_ORIGIN, "payment.completed.dlq", 0, 5L,
                DlqOrigin.UNKNOWN_CONSUMER_GROUP, null, null, null, null, "garbage",
                null, null, null, null);

        assertThat(recorder.record(origin)).isTrue();

        DeadLetterRecord record = repository.findAll().get(0);
        assertThat(record.getOriginKind()).isEqualTo(DlqOriginKind.DLQ_ORIGIN);
        assertThat(record.getOriginTopic()).isEqualTo("payment.completed.dlq");
        assertThat(record.getFailedConsumerGroup()).isEqualTo(DlqOrigin.UNKNOWN_CONSUMER_GROUP);
        assertThat(record.getClusterId()).isNotBlank();
        assertThat(record.getTopicGeneration()).isPositive();
    }

    @Test
    @DisplayName("서로 다른 group 의 같은 원본 좌표는 별개 행이다 — 공유 DLQ 에서 누가 미결인지 구분된다")
    void sameCoordinateDifferentGroupsAreSeparateRows() {
        assertThat(recorder.record(resolvedOrigin(3, 300L, OWNED_GROUP))).isTrue();
        assertThat(recorder.record(resolvedOrigin(3, 300L, FOREIGN_GROUP))).isTrue();

        assertThat(repository.findAll()).hasSize(2);
    }

    @Test
    @DisplayName("payload 가 상한을 넘으면 잘리고 payload_truncated 로 표시된다")
    void truncatesOversizedPayload() {
        String huge = "x".repeat(20_000);
        DlqOrigin origin = new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "payment.completed", 4, 400L,
                OWNED_GROUP, "k", null, "E", "m", huge,
                null, null, null, null);

        recorder.record(origin);

        DeadLetterRecord record = repository.findAll().get(0);
        assertThat(record.isPayloadTruncated()).isTrue();
        assertThat(record.getPayload()).hasSize(8000);
    }

    @Test
    @DisplayName("미등록 토픽은 부팅이 아니라 적재 시점에 예외 — 기본값으로 조용히 떨어지지 않는다")
    void unregisteredTopicFailsLoudly() {
        DlqOrigin origin = new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "totally.unknown.topic", 0, 1L,
                OWNED_GROUP, null, null, null, null, "{}",
                null, null, null, null);

        assertThat(repository.findAll()).isEmpty();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> recorder.record(origin))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("topic-generations");
    }

    // ---------- 종결 전이 ----------

    @Test
    @DisplayName("DISCARDED 는 사유 없이 불가 — 근거 없이 닫힌 원장은 거짓말을 한다")
    void discardRequiresReason() {
        recorder.record(resolvedOrigin(5, 500L, OWNED_GROUP));
        DeadLetterRecord record = repository.findAll().get(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> record.discard("ops", "  "))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(record.acknowledge("ops")).isTrue();
        assertThat(record.acknowledge("ops")).isFalse();   // 재전이 no-op
        assertThat(record.discard("ops", "상류 버그 수정 완료, 재처리 불필요")).isTrue();
        assertThat(record.statusValue()).isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(record.discard("ops", "다시")).isFalse(); // terminal
    }

    // ---------- helpers ----------

    private DlqOrigin resolvedOrigin(int partition, long offset, String group) {
        return new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "payment.completed", partition, offset,
                group, "order-1", 1_700_000_000_000L, "java.lang.IllegalStateException", "boom", "{}",
                null, null, null, null);
    }

    private void send(String dlqTopic, String originTopic, int partition, long offset,
                      String group, String payload) {
        ProducerRecord<String, String> record = baseRecord(dlqTopic, originTopic, partition, offset, payload);
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, group.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record);
    }

    private void sendWithoutGroup(String dlqTopic, String originTopic, int partition, long offset,
                                  String payload) {
        kafkaTemplate.send(baseRecord(dlqTopic, originTopic, partition, offset, payload));
    }

    private ProducerRecord<String, String> baseRecord(String dlqTopic, String originTopic,
                                                      int partition, long offset, String payload) {
        ProducerRecord<String, String> record = new ProducerRecord<>(dlqTopic, "order-1", payload);
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_TOPIC, originTopic.getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_PARTITION,
                ByteBuffer.allocate(Integer.BYTES).putInt(partition).array());
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_OFFSET,
                ByteBuffer.allocate(Long.BYTES).putLong(offset).array());
        record.headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN,
                "java.lang.IllegalArgumentException".getBytes(StandardCharsets.UTF_8));
        return record;
    }
}
