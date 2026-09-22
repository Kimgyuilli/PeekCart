package com.peekcart.global.outbox;

import com.peekcart.global.deadletter.DeadLetterPublicationReconciler;
import com.peekcart.global.deadletter.DeadLetterRecordJpaRepository;
import com.peekcart.global.deadletter.DeadLetterRecord;
import com.peekcart.global.deadletter.DeadLetterRecorder;
import com.peekcart.global.deadletter.PublicationStatus;
import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.ReplayHeaders;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * outbox replay 발행 표면 회귀 (구현 ④-c-2b-2 · 계획 §6 V-30·V-33·V-21b·V-21d · ADR-0020 §D3·§D6-4).
 *
 * <p>이 PR 에는 <b>replay 개시 진입점이 없다</b>(④-c-2b-4 소관). 따라서 replay 행과 원장의
 * {@code REQUESTED} 상태는 fixture 로 구성한다 — 진입점이 만들 <b>DB 상태</b>를 직접 세워
 * 발행 경로·reconciler·cleanup 경쟁 계약을 먼저 고정한다.
 *
 * <p><b>여기서 지키는 것 넷</b>:
 * <ul>
 *   <li>{@code record_kind IS NULL}(구버전 writer)은 도메인으로 발행된다 — expand 단계의 NULL 해석</li>
 *   <li>reconciler 는 {@code PENDING} 을 종착시키지 않는다 — 발행 중인 건의 조기 종결 금지</li>
 *   <li>cleanup 은 {@code REQUESTED} root 에 연결된 replay outbox 를 지우지 않는다</li>
 *   <li>outbox 행 <b>부재</b>를 {@code PUBLISH_FAILED} 로 강등하지 않는다 — 부재는 실패의 증거가 아니다</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // **배경 잡을 세운다.** 배경 reconciler 가 fixture 의 REQUESTED 를 먼저 종착시키면
        // "부재를 강등하지 않는다"·"cleanup 이 제외한다" 가 관측되기 전에 전제가 사라진다.
        // 배경 poller 도 같은 이유로 세운다 — 전이는 전부 테스트가 직접 호출한다.
        "app.outbox.polling.delay=1h",
        "app.dead-letter.reconcile.delay=1h"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("outbox replay 발행 표면")
class OutboxReplayPublicationIntegrationTest extends AbstractIntegrationTest {

    @Autowired OutboxPollingService pollingService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired OutboxEventCleanupScheduler cleanupScheduler;
    @Autowired DeadLetterPublicationReconciler reconciler;
    @Autowired DeadLetterRecordJpaRepository ledgerRepository;
    @Autowired DeadLetterRecorder recorder;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String GROUP = "order-svc-payment-completed-group";
    private static final String ORIGIN_TOPIC = "payment.completed";
    private static final String ATTEMPT_ID = "3f2a1b7c-8d9e-4a0b-9c1d-2e3f4a5b6c7d";

    /** allowlist 4종을 전부 채운 정상 헤더 (④-c-2b-3a P14-b). */
    private static final String REPLAY_HEADERS_JSON =
            "{\"" + ReplayHeaders.ATTEMPT_ID + "\":\"" + ATTEMPT_ID + "\","
            + "\"" + ReplayHeaders.LEDGER_OWNER + "\":\"order\","
            + "\"" + ReplayHeaders.TARGET_GROUP + "\":\"" + GROUP + "\","
            + "\"" + ReplayHeaders.ROOT_ID + "\":\"42\"}";

    @BeforeEach
    void setUp() {
        cleanDatabase();
        // 공유 브로커라 앞 클래스가 남긴 레코드가 seekToBeginning 에 읽힌다 (D-032).
        cleanKafkaTopics(SharedContainers.KAFKA.getBootstrapServers(), "order.created", "payment.completed");
        ledgerRepository.deleteAll();
        outboxEventJpaRepository.deleteAll();
        awaitTopicsReady("order.created", "payment.completed");
        // lockAtLeastFor 가 남아 있으면 두 번째 이후 호출이 통째로 건너뛰어지고, 그 테스트는
        // "아무 일도 안 일어났다" 를 관측하며 green 이 된다. 행을 지우면 오히려 영구 잠기므로 만료시킨다.
        for (String lock : List.of("outboxEventsCleanupJob", "deadLetterPublicationReconcileJob")) {
            expireLock(lock);
        }
    }

    private void expireLock(String name) {
        jdbcTemplate.update("UPDATE shedlock SET lock_until = NOW() - INTERVAL 1 DAY WHERE name = ?", name);
    }

    // ---------- V-30: record_kind IS NULL 은 도메인이다 ----------

    @Test
    @DisplayName("record_kind 가 NULL 인 행(구버전 writer)은 도메인 경로로 발행된다")
    void nullRecordKindPublishesThroughDomainPath() {
        // 구버전 writer 를 흉내낸다 — 팩토리를 쓰면 DOMAIN 이 박히므로 판별자를 직접 비운다.
        String eventId = UUID.randomUUID().toString();
        insertRawOutbox(eventId, "order.created", "order-77", null);

        pollingService.pollAndPublish();

        // **개수를 단언하지 않는다.** 배경 poller(@Scheduled 5초)가 같은 행을 집어갈 수 있고,
        // 재발행 중복은 ADR-0020 D1 이 허용하는 것이다 — "정확히 1개" 는 계약이 말하지 않는 것을
        // 주장하면서 스케줄러 타이밍에 흔들린다. 대신 **발행된 모든 레코드가 계약을 만족**하는지 본다.
        List<ConsumerRecord<String, String>> records = drain("order.created", 1);
        assertThat(records).isNotEmpty();
        // 도메인 경로의 계약: 토픽 = event_type, key = aggregate_id.
        assertThat(records).allSatisfy(record -> assertThat(record.key()).isEqualTo("order-77"));
        assertThat(status(eventId)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("replay 행은 destination 좌표·원본 timestamp·allowlist 헤더로 발행된다")
    void replayRecordCarriesItsOwnCoordinates() {
        long sourceTimestamp = 1_700_000_000_000L;
        outboxEventRepository.save(replayRow("order-coords", REPLAY_HEADERS_JSON, sourceTimestamp));

        pollingService.pollAndPublish();

        // 개수가 아니라 **좌표**가 계약이다(위 테스트와 같은 이유). 중복이 나와도 전부 같은 좌표여야 한다.
        List<ConsumerRecord<String, String>> records = drainByKey("order-coords");
        assertThat(records).isNotEmpty();
        assertThat(records).allSatisfy(record -> {
            assertThat(record.partition()).isEqualTo(0);
            // 원본 timestamp 를 그대로 실어야 재실패 시 DLT_ORIGINAL_TIMESTAMP 가 원본을 가리킨다(D5-3).
            assertThat(record.timestamp()).isEqualTo(sourceTimestamp);
            // allowlist 4종이 그대로 실린다 — 재실패 시 이 값들이 원장 앵커와 대조된다(ADR-0021 D1).
            assertThat(header(record, ReplayHeaders.ATTEMPT_ID)).isEqualTo(ATTEMPT_ID);
            assertThat(header(record, ReplayHeaders.LEDGER_OWNER)).isEqualTo("order");
            assertThat(header(record, ReplayHeaders.TARGET_GROUP)).isEqualTo(GROUP);
            assertThat(header(record, ReplayHeaders.ROOT_ID)).isEqualTo("42");
            // 표준 DLT_* 는 싣지 않는다 — 재실패 시 원본 좌표가 오염된다.
            assertThat(record.headers().lastHeader("kafka_dlt-original-topic")).isNull();
        });
    }

    // ---------- 발행 측 allowlist 강제 (④-c-2b-3a P14-b) ----------
    //
    // 여기서 막지 않으면 헤더가 모자란 replay 가 발행되고, 재실패 시 상관 축이 없어 독립 incident 로
    // 갈라진다 — 발행 측에서 ADR-0020 §D5-4 를 깨는 경로다.
    //
    // **"broker 에 없다" 로 단언하지 않는다.** 음성 증명은 폴링 창을 얼마나 길게 잡아도 "아직 안 왔다" 와
    // 구분되지 않아, 발행이 실제로 일어나도 통과할 수 있다. 대신 **발행 시도가 실패했다는 양성 증거**를
    // 본다 — 계약 위반은 buildRecord 단계에서 던져지므로 handlePublishFailure 가 retry_count 를 올리고
    // 행은 PENDING 으로 남는다(재시도 대상). 발행에 성공했다면 PUBLISHED 이고 retry_count 는 0이다.

    @Test
    @DisplayName("헤더가 모자란 replay 행은 발행되지 않는다 — 부분집합은 통과가 아니다")
    void rejectsIncompleteReplayHeaders() {
        assertReplayRejected("order-incomplete",
                "{\"" + ReplayHeaders.ATTEMPT_ID + "\":\"" + ATTEMPT_ID + "\"}");
    }

    @Test
    @DisplayName("replay_headers 가 비어 있으면 발행되지 않는다 — 빈 Map 이 조용히 통과하던 경로")
    void rejectsEmptyReplayHeaders() {
        assertReplayRejected("order-empty", null);
    }

    @Test
    @DisplayName("표준 DLT_* 를 실은 replay 행은 발행되지 않는다 — 실으면 원본 좌표가 덮인다")
    void rejectsStandardDltHeaders() {
        assertReplayRejected("order-dlt", REPLAY_HEADERS_JSON.substring(0, REPLAY_HEADERS_JSON.length() - 1)
                + ",\"kafka_dlt-original-topic\":\"" + ORIGIN_TOPIC + "\"}");
    }

    @Test
    @DisplayName("root-id 가 숫자가 아닌 replay 행은 발행되지 않는다")
    void rejectsMalformedRootId() {
        assertReplayRejected("order-badroot", REPLAY_HEADERS_JSON.replace("\"42\"", "\"not-a-number\""));
    }

    /**
     * <b>음성 대조군</b> — 계약을 지킨 행은 실제로 발행돼야 한다.
     * 이것이 없으면 위 4종은 "replay 를 전부 막는 코드" 로도 green 이다.
     */
    @Test
    @DisplayName("계약을 지킨 replay 행은 발행된다 — 위 4종이 replay 전체를 막는 것이 아니다")
    void acceptsCompleteReplayHeaders() {
        OutboxEvent saved = outboxEventRepository.save(
                replayRow("order-accepted", REPLAY_HEADERS_JSON, 1_700_000_000_000L));

        pollingService.pollAndPublish();

        assertThat(outboxStatusOf(saved.getId())).isEqualTo(OutboxEventStatus.PUBLISHED.name());
        assertThat(outboxRetryCountOf(saved.getId())).isZero();
        assertThat(drainByKey("order-accepted")).isNotEmpty();
    }

    private void assertReplayRejected(String recordKey, String replayHeadersJson) {
        OutboxEvent saved = outboxEventRepository.save(
                replayRow(recordKey, replayHeadersJson, 1_700_000_000_000L));

        pollingService.pollAndPublish();

        assertThat(outboxStatusOf(saved.getId())).isEqualTo(OutboxEventStatus.PENDING.name());
        assertThat(outboxRetryCountOf(saved.getId())).isGreaterThanOrEqualTo(1);
    }

    // ---------- V-33: reconciler 는 발행 축만, 종착만 ----------

    @Test
    @DisplayName("reconciler 는 PUBLISHED/FAILED 만 종착시키고 PENDING 은 그대로 둔다")
    void reconcilerSettlesOnlyTerminalOutboxStates() {
        var published = requestedLedgerWithOutbox(100L, OutboxEventStatus.PUBLISHED);
        var failed = requestedLedgerWithOutbox(101L, OutboxEventStatus.FAILED);
        var pending = requestedLedgerWithOutbox(102L, OutboxEventStatus.PENDING);

        reconciler.reconcile();

        assertThat(publicationStatusOf(published)).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(publicationStatusOf(failed)).isEqualTo(PublicationStatus.PUBLISH_FAILED);
        // 발행 중인 건을 종착시키면 아직 나가지도 않은 메시지가 "발행됨" 으로 감사 기록된다.
        assertThat(publicationStatusOf(pending)).isEqualTo(PublicationStatus.REQUESTED);
    }

    @Test
    @DisplayName("reconciler 는 사건 축(status)을 건드리지 않는다 — 발행 성공은 사건 해소가 아니다")
    void reconcilerNeverTouchesIncidentAxis() {
        Long id = requestedLedgerWithOutbox(103L, OutboxEventStatus.PUBLISHED);
        String before = statusOf(id);

        reconciler.reconcile();

        assertThat(publicationStatusOf(id)).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(statusOf(id)).isEqualTo(before);
        assertThat(ledgerRepository.countUnresolved()).isEqualTo(1);
    }

    // ---------- V-21d: 부재를 실패로 추론하지 않는다 ----------

    @Test
    @DisplayName("outbox 행이 사라져도 PUBLISH_FAILED 로 강등하지 않는다 — 부재는 실패의 증거가 아니다")
    void missingOutboxRowIsNotDemoted() {
        Long id = requestedLedgerWithOutbox(104L, OutboxEventStatus.PUBLISHED);
        Long outboxId = outboxEventIdOf(id);
        jdbcTemplate.update("DELETE FROM outbox_events WHERE id = ?", outboxId);

        reconciler.reconcile();

        // 강등하면 발행된 사건이 "실패" 로 기록되고 재요청까지 열린다 — 같은 메시지가 두 번 나간다.
        assertThat(publicationStatusOf(id)).isEqualTo(PublicationStatus.REQUESTED);
    }

    // ---------- V-21b: cleanup 이 REQUESTED root 의 replay 행을 지우지 않는다 ----------

    @Test
    @DisplayName("cleanup 은 REQUESTED root 에 연결된 replay outbox 를 남기고 나머지는 지운다")
    void cleanupSkipsOutboxLinkedToRequestedLedger() {
        Long fenced = requestedLedgerWithOutbox(105L, OutboxEventStatus.PUBLISHED);
        Long fencedOutboxId = outboxEventIdOf(fenced);

        // 대조군: 원장에 연결되지 않은 오래된 PUBLISHED 행. 같은 cutoff 를 만족한다.
        String freeEventId = UUID.randomUUID().toString();
        insertRawOutbox(freeEventId, "order.created", "order-1", "DOMAIN");
        agePublished(freeEventId);
        agePublished(eventIdOfOutbox(fencedOutboxId));

        cleanupScheduler.cleanup();

        assertThat(outboxEventJpaRepository.findById(fencedOutboxId)).isPresent();
        assertThat(status(freeEventId)).isNull();

        // reconciler 가 복구되면 정상 전이하고, 그 다음 cleanup 에서는 지워진다.
        reconciler.reconcile();
        assertThat(publicationStatusOf(fenced)).isEqualTo(PublicationStatus.PUBLISHED);
        // lockAtLeastFor(PT1M) 를 만료시키지 않으면 두 번째 cleanup 이 통째로 건너뛰어진다 —
        // 그러면 "제외 조건이 계속 막고 있다" 와 "잡이 아예 안 돌았다" 가 구분되지 않는다.
        expireLock("outboxEventsCleanupJob");
        cleanupScheduler.cleanup();
        assertThat(outboxEventJpaRepository.findById(fencedOutboxId)).isEmpty();
    }

    // ---------- helpers ----------

    /** 팩토리를 우회해 raw INSERT 한다 — {@code record_kind} 를 비운 구버전 writer 를 재현하기 위함이다. */
    private void insertRawOutbox(String eventId, String eventType, String aggregateId, String recordKind) {
        jdbcTemplate.update("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, event_id, payload,
                                           status, retry_count, created_at, record_kind)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, NOW(6), ?)
                """, "ORDER", aggregateId, eventType, eventId, "{\"eventId\":\"" + eventId + "\"}", recordKind);
    }

    /** 원장 행 1건을 {@code REQUESTED} 로 두고, 지정한 상태의 outbox 행에 연결한다. */
    private Long requestedLedgerWithOutbox(long offset, OutboxEventStatus outboxStatus) {
        recorder.record(new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, ORIGIN_TOPIC, 0, offset,
                GROUP, "order-1", 1_700_000_000_000L, "java.lang.IllegalStateException", "boom", "{}",
                null, null, null, null));
        DeadLetterRecord record = ledgerRepository.findAll().stream()
                .filter(r -> r.getOriginOffset() == offset).findFirst().orElseThrow();

        OutboxEvent replay = OutboxEvent.replay(
                record.getId(), ORIGIN_TOPIC, 0, "order-1", "{\"eventId\":\"t\"}",
                1_700_000_000_000L, "t", null, record.getId(), GROUP, null, null);
        OutboxEvent saved = outboxEventRepository.save(replay);
        jdbcTemplate.update("UPDATE outbox_events SET status = ?, published_at = NOW(6) WHERE id = ?",
                outboxStatus.name(), saved.getId());
        jdbcTemplate.update(
                "UPDATE dead_letter_records SET publication_status = 'REQUESTED', outbox_event_id = ? WHERE id = ?",
                saved.getId(), record.getId());
        return record.getId();
    }

    private void agePublished(String eventId) {
        jdbcTemplate.update(
                "UPDATE outbox_events SET status = 'PUBLISHED', published_at = NOW(6) - INTERVAL 400 DAY "
                        + "WHERE event_id = ?", eventId);
    }

    private String eventIdOfOutbox(Long id) {
        return jdbcTemplate.queryForObject("SELECT event_id FROM outbox_events WHERE id = ?", String.class, id);
    }

    private Long outboxEventIdOf(Long ledgerId) {
        return jdbcTemplate.queryForObject(
                "SELECT outbox_event_id FROM dead_letter_records WHERE id = ?", Long.class, ledgerId);
    }

    private PublicationStatus publicationStatusOf(Long ledgerId) {
        String value = jdbcTemplate.queryForObject(
                "SELECT publication_status FROM dead_letter_records WHERE id = ?", String.class, ledgerId);
        return value == null ? null : PublicationStatus.valueOf(value);
    }

    private String statusOf(Long ledgerId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM dead_letter_records WHERE id = ?", String.class, ledgerId);
    }

    private String status(String eventId) {
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String outboxStatusOf(Long id) {
        return jdbcTemplate.queryForObject("SELECT status FROM outbox_events WHERE id = ?", String.class, id);
    }

    private Integer outboxRetryCountOf(Long id) {
        return jdbcTemplate.queryForObject("SELECT retry_count FROM outbox_events WHERE id = ?", Integer.class, id);
    }

    /**
     * replay outbox 행. {@code recordKey} 를 케이스마다 다르게 주는 것이 <b>격리 장치다</b> —
     * 이 클래스의 여러 테스트가 같은 토픽에 쓰므로, key 로 거르지 않으면 한 테스트가 남긴 레코드가
     * 다른 테스트의 {@code allSatisfy} 를 깨서 **엉뚱한 곳에서 red 가 난다**(발행 측 검증을 제거하는
     * 변이 실험에서 실제로 관측됐다).
     */
    private OutboxEvent replayRow(String recordKey, String replayHeadersJson, long sourceTimestamp) {
        return OutboxEvent.replay(
                42L, ORIGIN_TOPIC, 0, recordKey, "{\"eventId\":\"target-1\"}",
                sourceTimestamp, "target-1", replayHeadersJson, 42L, GROUP, null, null);
    }

    /** 이 테스트가 발행한 레코드만 고른다. 다른 테스트의 잔여물과 섞이지 않게 한다. */
    private List<ConsumerRecord<String, String>> drainByKey(String key) {
        return drain(ORIGIN_TOPIC, 1).stream().filter(r -> key.equals(r.key())).toList();
    }

    private String header(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }


    /**
     * 발행 전에 토픽 메타데이터가 준비되기를 기다린다.
     *
     * <p>컨테이너가 여럿 뜬 느린 실행에서는 토픽 생성이 끝나기 전에 첫 send 가 나가 메타데이터 대기로
     * 타임아웃한다. 그러면 그 사이클의 레코드가 아예 생기지 않는데(실측: broker end offset 이 전부 0),
     * 테스트는 그것을 발행 경로의 결함으로 잘못 읽는다. 여기서 재는 것은 토픽 프로비저닝이 아니다.
     */
    private void awaitTopicsReady(String... topics) {
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            Properties props = new Properties();
            props.put("bootstrap.servers", SharedContainers.KAFKA.getBootstrapServers());
            props.put("group.id", "test-topic-ready-" + UUID.randomUUID());
            props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                for (String topic : topics) {
                    var partitions = consumer.partitionsFor(topic);
                    if (partitions == null || partitions.isEmpty()
                            || partitions.stream().anyMatch(info -> info.leader() == null)) {
                        return false;
                    }
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }

    private List<ConsumerRecord<String, String>> drain(String topic, int expected) {
        Properties props = new Properties();
        props.put("bootstrap.servers", SharedContainers.KAFKA.getBootstrapServers());
        props.put("group.id", "test-drain-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            // **subscribe 가 아니라 assign 이다.** subscribe 는 consumer group 조인·리밸런스를 거치는데,
            // 컨테이너가 여럿 뜬 상태에서는 그 조율이 폴링 창을 통째로 잡아먹어 **이미 broker 에 ack 된
            // 레코드를 못 보고** 빈 결과가 나온다(실측: send 는 성공했는데 drain 이 0건). 그룹이 필요 없는
            // 검증이므로 파티션을 직접 할당하고 처음부터 읽는다 — 조율 지연이라는 변수 자체를 없앤다.
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, String>> collected = new java.util.ArrayList<>();
            long deadline = System.currentTimeMillis() + 20_000;
            while (collected.size() < expected && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.records(topic).forEach(collected::add);
            }
            return collected;
        }
    }
}
