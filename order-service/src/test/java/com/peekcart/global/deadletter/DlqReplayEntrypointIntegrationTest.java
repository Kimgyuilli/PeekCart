package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.global.kafka.PayloadDigest;
import com.peekcart.global.kafka.ReplayHeaders;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
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

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
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
        "app.dead-letter.reconcile.delay=1h",
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
    @Autowired DeadLetterPublicationReconciler reconciler;
    @Autowired com.peekcart.global.outbox.OutboxPollingService pollingService;
    @Autowired com.peekcart.order.infrastructure.OrderJpaRepository orderJpaRepository;
    @Autowired DeadLetterProperties properties;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired org.springframework.kafka.config.KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    /** order 가 소비하고 정책이 allow 인 토픽. */
    private static final String ALLOWED_TOPIC = "payment.completed";
    private static final String ALLOWED_GROUP = "order-svc-payment-completed-group";
    /** order 가 소비하지만 정책이 **deny** 인 토픽 — 늦은 재적용이 이중 과금 경로다. */
    private static final String DENIED_TOPIC = "payment.requested";
    private static final String DENIED_GROUP = "order-svc-payment-requested-group";
    /** 사전조건(도메인 상태 조회)을 요구하는 유일한 토픽. */
    private static final String STOCK_TOPIC = "stock.reservation.result";
    private static final String STOCK_GROUP = "order-svc-stock-result-group";

    @BeforeEach
    void setUp() {
        cleanDatabase();
        ledgerRepository.deleteAll();
        outboxEventJpaRepository.deleteAll();
        // 기본값은 false 다. 여는 것은 각 테스트가 명시적으로 한다 — 기본값이 뒤집히면 V-38 이 red 가 된다.
        properties.getReplay().setEnabled(false);
        // **업무 리스너를 세운다.** 살아 있는 @KafkaListener 가 fixture 레코드를 실제로 소비해
        // 도메인 상태를 바꾼다 — 실측: stock.reservation.result 를 심자 consumer 가 먼저
        // confirmReservation 을 적용해 "사전조건 allow" 케이스가 deny 로 뒤집혔다.
        // `spring.kafka.listener.auto-startup` 은 듣지 않는다 — 서비스가 container factory 를
        // 직접 만들어(OrderKafkaConfig) Boot 의 listener 속성을 적용하지 않기 때문이다.
        listenerRegistry.stop();
        // lockAtLeastFor(PT4S) 가 남아 있으면 두 번째 이후의 reconcile() 호출이 통째로 건너뛰어지고,
        // 그 테스트는 "아무 일도 안 일어났다" 를 관측하며 green 이 된다.
        jdbcTemplate.update("UPDATE shedlock SET lock_until = NOW() - INTERVAL 1 DAY WHERE name = ?",
                "deadLetterPublicationReconcileJob");
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

    // ---------- drain 앵커 (diff 리뷰 1R #6) ----------

    @Test
    @DisplayName("발행 축이 PUBLISHED 로 종착하면 drain 앵커가 DB 시각으로 찍힌다")
    void anchorStampedOnPublished() {
        Long ledgerId = replayedIncident("order-8");
        assertThat(ledgerRepository.findById(ledgerId).orElseThrow().getLastReplaySettledAt()).isNull();

        settleOutboxAs("PUBLISHED", ledgerId);
        reconciler.reconcile();

        DeadLetterRecord after = ledgerRepository.findById(ledgerId).orElseThrow();
        assertThat(after.getPublicationStatus()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(after.getLastReplaySettledAt()).isNotNull();
    }

    @Test
    @DisplayName("PUBLISH_FAILED 로 종착해도 앵커를 찍는다 — '발행되지 않았다' 의 증명이 아니기 때문")
    void anchorStampedOnPublishFailed() {
        Long ledgerId = replayedIncident("order-9");

        settleOutboxAs("FAILED", ledgerId);
        reconciler.reconcile();

        DeadLetterRecord after = ledgerRepository.findById(ledgerId).orElseThrow();
        assertThat(after.getPublicationStatus()).isEqualTo(PublicationStatus.PUBLISH_FAILED);
        // FAILED 분기에서만 앵커를 빼면 drain 이 재시도 중에 통과한다 — 그 변이가 여기서 red 가 된다.
        assertThat(after.getLastReplaySettledAt()).isNotNull();
    }

    @Test
    @DisplayName("PENDING 이면 종착도 앵커도 없다 — 발행 중인 건을 조기 종결하지 않는다")
    void pendingIsNotSettled() {
        Long ledgerId = replayedIncident("order-10");

        reconciler.reconcile();

        DeadLetterRecord after = ledgerRepository.findById(ledgerId).orElseThrow();
        assertThat(after.getPublicationStatus()).isEqualTo(PublicationStatus.REQUESTED);
        assertThat(after.getLastReplaySettledAt()).isNull();
    }

    // ---------- V-35: 진입점 → 발행 → 재실패 → 상관 관통 (diff 리뷰 1R #4) ----------

    @Test
    @DisplayName("진입점이 만든 앵커로 재발행분이 실제 발행되고, 그 재실패가 같은 root 에 상관된다")
    void entrypointToCorrelation() throws Exception {
        Published published = publish(ALLOWED_TOPIC, "order-11");
        Long rootId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, published);
        properties.getReplay().setEnabled(true);
        DeadLetterReplayService.Result accepted = replay(rootId);
        assertThat(accepted.accepted()).isTrue();

        // 1) poller 가 replay 행을 실제로 발행한다.
        pollingService.pollAndPublish();

        ConsumerRecord<String, String> republished = lastRecordWithHeader(ALLOWED_TOPIC,
                ReplayHeaders.ATTEMPT_ID, accepted.attemptId());
        assertThat(republished).as("재발행 레코드를 찾지 못했다").isNotNull();
        // 2) 진입점이 만든 헤더가 그대로 실린다 — fixture 가 아니라 실제 값이다.
        assertThat(header(republished, ReplayHeaders.LEDGER_OWNER)).isEqualTo("order");
        assertThat(header(republished, ReplayHeaders.TARGET_GROUP)).isEqualTo(ALLOWED_GROUP);
        assertThat(header(republished, ReplayHeaders.ROOT_ID)).isEqualTo(String.valueOf(rootId));
        // 3) 원본 좌표·payload 가 보존된다(§D8-3 fence 가 주장하는 것).
        assertThat(republished.value()).isEqualTo(originalValue(ALLOWED_TOPIC, published.offset()));
        assertThat(republished.timestamp()).isEqualTo(originalTimestamp(ALLOWED_TOPIC, published.offset()));
        // 4) root 의 digest 가 **재발행된 payload** 의 digest 와 같다 — 상관 대조 축 9의 정본이다.
        assertThat(ledgerRepository.findById(rootId).orElseThrow().getLastReplayPayloadDigest())
                .isEqualTo(PayloadDigest.sha256Hex(republished.value()));

        // 5) 그 재발행분이 표적 group 에서 다시 실패했다고 보고 DLT 를 적재한다.
        //    헤더는 fixture 가 아니라 **방금 발행된 레코드에서 읽은 실제 값**이다.
        recorder.record(new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, ALLOWED_TOPIC,
                republished.partition(), republished.offset(), ALLOWED_GROUP, republished.key(),
                republished.timestamp(), "java.lang.IllegalStateException", "재실패", republished.value(),
                header(republished, ReplayHeaders.ATTEMPT_ID),
                header(republished, ReplayHeaders.LEDGER_OWNER),
                header(republished, ReplayHeaders.TARGET_GROUP),
                Long.valueOf(header(republished, ReplayHeaders.ROOT_ID))));

        // 6) **독립 incident 로 갈라지지 않고** 같은 root 에 자식으로 붙는다.
        List<DeadLetterRecord> all = ledgerRepository.findAll();
        assertThat(all).hasSize(2);
        DeadLetterRecord child = all.stream().filter(r -> !r.getId().equals(rootId)).findFirst().orElseThrow();
        assertThat(child.getRootRecordId()).isEqualTo(rootId);
        // 사건은 여전히 1건이다 — 재실패가 backlog 를 늘리면 운영자가 같은 사건을 두 번 센다.
        assertThat(ledgerRepository.countUnresolved()).isEqualTo(1);
    }

    // ---------- V-28d 배선: 실제 adapter 를 거친다 (diff 리뷰 1R #5) ----------

    @Test
    @DisplayName("stock.reservation.result 는 실제 Order 를 조회해 갈린다 — adapter 가 배선돼 있어야 한다")
    void preconditionUsesRealOrderAdapter() {
        properties.getReplay().setEnabled(true);

        Order pending = orderJpaRepository.save(Order.create(1L, "ORD-" + UUID.randomUUID(), "받는이",
                "010-0000-0000", "12345", "주소", List.of(new OrderItemData(1L, 1, 1000L))));
        Published allowed = publishReservationResult(pending.getId(), true);
        Long allowedLedger = ledgerRow(STOCK_TOPIC, STOCK_GROUP, allowed);

        assertThat(replay(allowedLedger).rejections())
                .as("PENDING·미확정 주문은 통과해야 한다 — adapter 가 없으면 fail-closed 로 거부된다")
                .isEmpty();

        // 같은 토픽이라도 주문 상태가 다르면 갈린다 — 상태 검사를 지우면 이 단언이 red 가 된다.
        Order cancelled = orderJpaRepository.save(Order.create(1L, "ORD-" + UUID.randomUUID(), "받는이",
                "010-0000-0000", "12345", "주소", List.of(new OrderItemData(1L, 1, 1000L))));
        cancelled.cancel();
        orderJpaRepository.save(cancelled);
        Published denied = publishReservationResult(cancelled.getId(), true);
        Long deniedLedger = ledgerRow(STOCK_TOPIC, STOCK_GROUP, denied);

        assertThat(replay(deniedLedger).rejections())
                .anyMatch(r -> r.contains("사전조건 불충족"));
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

    /** replay 를 개시해 REQUESTED 상태의 incident 를 만든다. */
    private Long replayedIncident(String key) {
        Published published = publish(ALLOWED_TOPIC, key);
        Long ledgerId = ledgerRow(ALLOWED_TOPIC, ALLOWED_GROUP, published.offset(), published.eventId());
        properties.getReplay().setEnabled(true);
        assertThat(replay(ledgerId).accepted()).isTrue();
        return ledgerId;
    }

    /** 해당 incident 에 연결된 replay outbox 행을 지정 상태로 종착시킨다(발행 결과를 흉내낸다). */
    private void settleOutboxAs(String outboxStatus, Long ledgerId) {
        Long outboxEventId = jdbcTemplate.queryForObject(
                "SELECT outbox_event_id FROM dead_letter_records WHERE id = ?", Long.class, ledgerId);
        jdbcTemplate.update("UPDATE outbox_events SET status = ?, published_at = NOW(6) WHERE id = ?",
                outboxStatus, outboxEventId);
    }

    private Published publishReservationResult(Long orderId, boolean reserved) {
        String eventId = UUID.randomUUID().toString();
        String value = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + STOCK_TOPIC
                + "\",\"payload\":{\"orderId\":" + orderId + ",\"reserved\":" + reserved + "}}";
        try {
            var metadata = kafkaTemplate.send(STOCK_TOPIC, 0, "order-" + orderId, value)
                    .get().getRecordMetadata();
            return new Published(metadata.offset(), eventId, metadata.timestamp(), "order-" + orderId);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 레코드 발행 실패", e);
        }
    }

    private String header(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** 지정 헤더 값을 가진 마지막 레코드를 찾는다. */
    private ConsumerRecord<String, String> lastRecordWithHeader(String topic, String headerKey, String expected) {
        ConsumerRecord<String, String> found = null;
        for (ConsumerRecord<String, String> record : readAll(topic)) {
            if (expected.equals(header(record, headerKey))) {
                found = record;
            }
        }
        return found;
    }

    private String originalValue(String topic, long offset) {
        return readAll(topic).stream().filter(r -> r.offset() == offset).findFirst().orElseThrow().value();
    }

    private long originalTimestamp(String topic, long offset) {
        return readAll(topic).stream().filter(r -> r.offset() == offset).findFirst().orElseThrow().timestamp();
    }

    private List<ConsumerRecord<String, String>> readAll(String topic) {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", "test-read-" + UUID.randomUUID());
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            // assign 전용 — group 조율 지연이라는 변수를 없앤다(기존 테스트와 같은 이유).
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(info -> new TopicPartition(topic, info.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            List<ConsumerRecord<String, String>> collected = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 15_000;
            long end = consumer.endOffsets(partitions).values().stream().mapToLong(Long::longValue).sum();
            while (collected.size() < end && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).records(topic).forEach(collected::add);
            }
            return collected;
        }
    }


    private DeadLetterReplayService.Result replay(Long ledgerId) {
        Optional<DeadLetterReplayService.Result> outcome = replayService.replay(ledgerId, "operator");
        return outcome.orElseThrow(() -> new AssertionError("원장에 행이 없다 — id=" + ledgerId));
    }

    /** 브로커에 실린 원본 레코드의 좌표와 eventId. replay 원본은 원장 사본이 아니라 이 레코드다. */
    private record Published(long offset, String eventId, long timestamp, String key) {
    }

    private Published publish(String topic, String key) {
        String eventId = UUID.randomUUID().toString();
        String value = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + topic
                + "\",\"payload\":{\"orderId\":1}}";
        try {
            var metadata = kafkaTemplate.send(topic, 0, key, value).get().getRecordMetadata();
            return new Published(metadata.offset(), eventId, metadata.timestamp(), key);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 레코드 발행 실패", e);
        }
    }

    private Long ledgerRow(String topic, String group, Published published) {
        return ledgerRow(topic, group, published.offset(), published.eventId(),
                published.timestamp(), published.key());
    }

    private Long ledgerRow(String topic, String group, long offset, String eventId) {
        return ledgerRow(topic, group, offset, eventId, System.currentTimeMillis(), "order-1");
    }

    /**
     * original_timestamp 는 **실제 원본 레코드의 timestamp** 여야 한다 — 재실패 상관(④-c-2b-3b)이
     * 그 값을 대조 축으로 쓰므로, 임의 값을 넣으면 정상 attempt 가 독립 root 로 갈라진다.
     * 과거 값(예: 고정 상수)을 쓰면 멱등 안전창(7d)이 이미 만료돼 모든 케이스가 [안전창] 으로 거부된다.
     */
    private Long ledgerRow(String topic, String group, long offset, String eventId,
                           long originalTimestamp, String originalKey) {
        recorder.record(new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, topic, 0, offset,
                group, originalKey, originalTimestamp, "java.lang.IllegalStateException", "boom",
                "{\"eventId\":\"" + eventId + "\"}", null, null, null, null));
        List<DeadLetterRecord> rows = ledgerRepository.findAll();
        DeadLetterRecord row = rows.stream()
                .filter(r -> r.getOriginOffset() == offset && topic.equals(r.getOriginTopic()))
                .findFirst().orElseThrow();
        jdbcTemplate.update("UPDATE dead_letter_records SET event_id = ? WHERE id = ?", eventId, row.getId());
        return row.getId();
    }
}
