package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.global.outbox.OutboxEventJpaRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * replay 개시 진입점 (구현 ④-c-2b-4a P19·P20·P21 · 계획 §6 V-8·V-9·V-13·V-22·V-38).
 *
 * <p><b>여기서 지키는 것</b>:
 * <ul>
 *   <li>kill-switch 가 닫혀 있으면 개시되지 않고 <b>outbox 행이 생기지 않는다</b>(V-38)</li>
 *   <li>금지축은 <b>독립 조건</b>이라 사유가 전부 모여 나온다(V-8)</li>
 *   <li>정책 deny 는 거부되고 <b>거부 이력이 원장에 남는다</b>(V-9·V-28c)</li>
 *   <li>claim 은 조건부라 두 번째 요청이 선점에 실패한다(V-22)</li>
 *   <li>I-1 은 resolve/discard 만 막고 <b>acknowledge 는 막지 않는다</b>(V-13)</li>
 * </ul>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 배경 잡이 fixture 상태를 바꾸면 단언이 관측 전에 무너진다.
        "app.outbox.polling.delay=1h",
        "app.dead-letter.reconcile.delay=1h"
})
@Import(IntegrationTestConfig.class)
@DisplayName("DLQ replay 개시 진입점")
class DlqReplayEntrypointIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired DeadLetterReplayService replayService;
    @Autowired DeadLetterTransitionService transitionService;
    @Autowired DeadLetterRecordJpaRepository ledgerRepository;
    @Autowired OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired DeadLetterRecorder recorder;
    @Autowired DeadLetterProperties properties;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** order 가 소비하고 정책이 allow 인 토픽. */
    private static final String ALLOWED_TOPIC = "payment.completed";
    private static final String ALLOWED_GROUP = "order-svc-payment-completed-group";
    /** order 가 소비하지만 정책이 **deny** 인 토픽 — 늦은 재적용이 이중 과금 경로다. */
    private static final String DENIED_TOPIC = "payment.requested";
    private static final String DENIED_GROUP = "order-svc-payment-requested-group";

    @BeforeEach
    void setUp() {
        cleanDatabase();
        ledgerRepository.deleteAll();
        outboxEventJpaRepository.deleteAll();
        // 기본값은 false 다. 여는 것은 각 테스트가 명시적으로 한다 — 기본값이 뒤집히면 V-38 이 red 가 된다.
        properties.getReplay().setEnabled(false);
    }

    // ---------- V-38: kill-switch ----------

    @Test
    @DisplayName("kill-switch 가 닫혀 있으면 거부되고 outbox 행이 생기지 않는다")
    void killSwitchClosed() {
        Long ledgerId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, 0L, UUID.randomUUID().toString());

        DeadLetterReplayService.Result result = replay(ledgerId);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejections()).anyMatch(r -> r.contains("replay 진입점이 꺼져 있다"));
        // 상태로 확인한다 — "거부했다" 는 응답만 보면 outbox 를 만들고 거부해도 통과한다.
        assertThat(outboxEventJpaRepository.count()).isZero();
        assertThat(ledgerRepository.findById(ledgerId).orElseThrow().getPublicationStatus()).isNull();
    }

    @Test
    @DisplayName("kill-switch 를 열면 같은 요청이 통과한다 — 거부 사유가 kill-switch 하나뿐이었음을 보인다")
    void killSwitchOpened() {
        Published published = publish(ALLOWED_TOPIC, "order-1");
        Long ledgerId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, published.offset(), published.eventId());
        properties.getReplay().setEnabled(true);

        DeadLetterReplayService.Result result = replay(ledgerId);

        assertThat(result.rejections()).isEmpty();
        assertThat(result.accepted()).isTrue();
        assertThat(result.attemptId()).isNotBlank();
        // 발행 축과 상관 앵커가 실제로 기록됐는지 DB 로 확인한다.
        DeadLetterRecord root = ledgerRepository.findById(ledgerId).orElseThrow();
        assertThat(root.getPublicationStatus()).isEqualTo(PublicationStatus.REQUESTED);
        assertThat(root.getLastReplayAttemptId()).isEqualTo(result.attemptId());
        assertThat(root.getLastReplayTargetGroup()).isEqualTo(ALLOWED_GROUP);
        assertThat(root.getLastReplayPayloadDigest()).isNotBlank();
        assertThat(root.getReplayDeadline()).isNotNull();
        assertThat(root.getOutboxEventId()).isNotNull();
        assertThat(outboxEventJpaRepository.count()).isEqualTo(1);
    }

    // ---------- V-9 · V-28c: 정책 deny 와 거부 이력 ----------

    @Test
    @DisplayName("정책이 금지한 토픽은 거부되고, 거부 이력이 원장에 남는다")
    void deniedPolicyLeavesAuditTrail() {
        Published published = publish(DENIED_TOPIC, "order-2");
        Long ledgerId = ledgerRow(DENIED_TOPIC, DENIED_GROUP, published.offset(), published.eventId());
        properties.getReplay().setEnabled(true);

        DeadLetterReplayService.Result result = replay(ledgerId);

        assertThat(result.accepted()).isFalse();
        assertThat(result.rejections()).anyMatch(r -> r.contains("[축5] 정책이 금지한다"));
        // **거부 이력이 남아야 한다.** claim 에만 기록하면 deny 는 claim 에 도달하지 않아 아무 기록도 없다.
        String audit = jdbcTemplate.queryForObject(
                "SELECT replay_policy FROM dead_letter_records WHERE id = ?", String.class, ledgerId);
        assertThat(audit).isEqualTo("order/payment.requested:v1:DENY");
        assertThat(outboxEventJpaRepository.count()).isZero();
    }

    // ---------- V-8: 금지축은 독립 조건이다 ----------

    @Test
    @DisplayName("여러 축을 동시에 위반하면 사유가 전부 모여 나온다 (첫 축에서 멈추지 않는다)")
    void allAxesReported() {
        // eventId 없음(축1) + __unknown__ group(축2) + DLQ_ORIGIN(축3) + original_timestamp 없음(축6)
        recorder.record(new DlqOrigin(DlqOriginKind.DLQ_ORIGIN, ALLOWED_TOPIC + ".dlq", 0, 7L,
                DlqOrigin.UNKNOWN_CONSUMER_GROUP, "k", null, "java.lang.IllegalStateException", "boom", "{}",
                null, null, null, null));
        Long ledgerId = ledgerRepository.findAll().get(0).getId();
        properties.getReplay().setEnabled(true);

        DeadLetterReplayService.Result result = replay(ledgerId);

        assertThat(result.rejections())
                .anyMatch(r -> r.startsWith("[축1]"))
                .anyMatch(r -> r.startsWith("[축2]"))
                .anyMatch(r -> r.startsWith("[축3]"))
                .anyMatch(r -> r.startsWith("[축6]"));
        assertThat(outboxEventJpaRepository.count()).isZero();
    }

    @Test
    @DisplayName("topic_generation 이 어긋나면 좌표 무효로 거부한다 (축4)")
    void generationMismatch() {
        Published published = publish(ALLOWED_TOPIC, "order-3");
        Long ledgerId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, published.offset(), published.eventId());
        jdbcTemplate.update("UPDATE dead_letter_records SET topic_generation = 99 WHERE id = ?", ledgerId);
        properties.getReplay().setEnabled(true);

        assertThat(replay(ledgerId).rejections()).anyMatch(r -> r.startsWith("[축4] topic_generation"));
        assertThat(outboxEventJpaRepository.count()).isZero();
    }

    @Test
    @DisplayName("원장 event_id 가 원본 payload 의 eventId 와 다르면 fence 가 거부한다")
    void fenceRejectsEventIdMismatch() {
        Published published = publish(ALLOWED_TOPIC, "order-4");
        // 원장의 event_id 를 원본과 다르게 둔다 — fence 가 잡아야 한다.
        Long ledgerId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, published.offset(), UUID.randomUUID().toString());
        properties.getReplay().setEnabled(true);

        assertThat(replay(ledgerId).rejections()).anyMatch(r -> r.startsWith("[fence]"));
        assertThat(outboxEventJpaRepository.count()).isZero();
    }

    // ---------- V-22: 조건부 claim ----------

    @Test
    @DisplayName("이미 REQUESTED 인 사건에 다시 요청하면 선점에 실패하고 outbox 가 늘지 않는다")
    void claimIsConditional() {
        Published published = publish(ALLOWED_TOPIC, "order-5");
        Long ledgerId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, published.offset(), published.eventId());
        properties.getReplay().setEnabled(true);

        assertThat(replay(ledgerId).accepted()).isTrue();
        long afterFirst = outboxEventJpaRepository.count();

        DeadLetterReplayService.Result second = replay(ledgerId);

        assertThat(second.accepted()).isFalse();
        assertThat(second.rejections()).anyMatch(r -> r.contains("발행 축을 선점할 수 없다"));
        // orphan outbox 0 — 거부가 outbox 를 만든 뒤에 일어나면 여기서 드러난다.
        assertThat(outboxEventJpaRepository.count()).isEqualTo(afterFirst);
    }

    // ---------- V-13: I-1 가드 ----------

    @Test
    @DisplayName("REQUESTED 인 사건은 종결할 수 없지만 acknowledge 는 된다")
    void i1BlocksResolutionButNotAcknowledge() {
        Published published = publish(ALLOWED_TOPIC, "order-6");
        Long ledgerId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, published.offset(), published.eventId());
        properties.getReplay().setEnabled(true);
        assertThat(replay(ledgerId).accepted()).isTrue();

        DeadLetterTransitionService.Result resolved =
                transitionService.resolve(ledgerId, "operator", "확인했다").orElseThrow();
        assertThat(resolved.changed()).isFalse();
        assertThat(resolved.rejectedReason()).contains("발행 결과가 미확정");

        // **acknowledge 는 면제된다** — I-1 이 금지하는 것은 terminal resolution 이다.
        DeadLetterTransitionService.Result acked =
                transitionService.acknowledge(ledgerId, "operator").orElseThrow();
        assertThat(acked.changed()).isTrue();
        assertThat(acked.rejectedReason()).isNull();

        // 상태로 확인한다 — 거부된 종결이 부분적으로라도 반영되면 안 된다.
        DeadLetterRecord after = ledgerRepository.findById(ledgerId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeadLetterStatus.ACKED.name());
        assertThat(after.getResolvedAt()).isNull();
    }

    @Test
    @DisplayName("publication_status 가 NULL 인 절대다수는 종결된다 — NULL 은 금지 상태가 아니다")
    void nullPublicationStatusCanBeResolved() {
        Long ledgerId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, 0L, UUID.randomUUID().toString());

        DeadLetterTransitionService.Result resolved =
                transitionService.resolve(ledgerId, "operator", "상류에서 재발행함").orElseThrow();

        assertThat(resolved.changed()).isTrue();
        assertThat(resolved.rejectedReason()).isNull();
    }

    // ---------- V-14: 잠금이 I-1 원자성의 근거다 ----------

    @Test
    @DisplayName("claim 을 쥔 트랜잭션이 커밋할 때까지 종결이 대기하고, 깨어나 REQUESTED 를 보고 거부한다")
    void resolutionWaitsForClaimLock() throws Exception {
        Published published = publish(ALLOWED_TOPIC, "order-7");
        Long ledgerId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, published.offset(), published.eventId());

        CountDownLatch locked = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // A: root 를 FOR UPDATE 로 잠근 채 잠시 들고 있다가 **그 다음에** REQUESTED 를 쓴다.
            //    (진입점 claim 이 하는 일을 그대로, 다만 잠금 보유 구간을 관측 가능하게 늘려서)
            Future<?> holder = executor.submit(() -> transactionTemplate.execute(status -> {
                ledgerRepository.findByIdForUpdate(ledgerId);
                locked.countDown();
                sleepQuietly(1500);
                ledgerRepository.claimPublication(ledgerId);
                return null;
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            // B: 이 시점의 DB 에는 아직 publication_status 가 NULL 이다. 잠금을 기다리지 않으면
            //    NULL 을 읽고 종결해버린다 — 그것이 정확히 I-1 이 막아야 하는 것이다.
            DeadLetterTransitionService.Result resolved =
                    transitionService.resolve(ledgerId, "operator", "확인했다").orElseThrow();
            holder.get(30, TimeUnit.SECONDS);

            // **잠금 선행을 지우는 변이(findByIdForUpdate → findById)에서 이 단언이 red 가 된다.**
            // 조건부 UPDATE 없이 평범한 Java 검사로 I-1 을 세운 근거가 여기서 관측된다(C-31).
            assertThat(resolved.changed()).isFalse();
            assertThat(resolved.rejectedReason()).contains("발행 결과가 미확정");
        } finally {
            executor.shutdownNow();
        }

        DeadLetterRecord after = ledgerRepository.findById(ledgerId).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(DeadLetterStatus.OPEN.name());
        assertThat(after.getResolvedAt()).isNull();
        assertThat(after.getPublicationStatus()).isEqualTo(PublicationStatus.REQUESTED);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------- helper ----------

    private DeadLetterReplayService.Result replay(Long ledgerId) {
        Optional<DeadLetterReplayService.Result> outcome = replayService.replay(ledgerId, "operator");
        return outcome.orElseThrow(() -> new AssertionError("원장에 행이 없다 — id=" + ledgerId));
    }

    /** 브로커에 실린 원본 레코드의 좌표와 eventId. replay 원본은 원장 사본이 아니라 이 레코드다. */
    private record Published(long offset, String eventId) {
    }

    private Published publish(String topic, String key) {
        String eventId = UUID.randomUUID().toString();
        String value = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + topic
                + "\",\"payload\":{\"orderId\":1}}";
        try {
            long offset = kafkaTemplate.send(topic, 0, key, value).get().getRecordMetadata().offset();
            return new Published(offset, eventId);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 레코드 발행 실패", e);
        }
    }

    private Long ledgerRow(String topic, String group, long offset, String eventId) {
        // original_timestamp 는 **현재 시각**이어야 한다 — 과거 값을 쓰면 멱등 안전창(7d)이 이미 만료돼
        // 모든 케이스가 [안전창] 으로 거부되고, 그러면 이 테스트들이 검사하려던 축에 도달하지 못한다.
        recorder.record(new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, topic, 0, offset,
                group, "order-1", System.currentTimeMillis(), "java.lang.IllegalStateException", "boom",
                "{\"eventId\":\"" + eventId + "\"}", null, null, null, null));
        List<DeadLetterRecord> rows = ledgerRepository.findAll();
        DeadLetterRecord row = rows.stream()
                .filter(r -> r.getOriginOffset() == offset && topic.equals(r.getOriginTopic()))
                .findFirst().orElseThrow();
        jdbcTemplate.update("UPDATE dead_letter_records SET event_id = ? WHERE id = ?", eventId, row.getId());
        return row.getId();
    }
}
