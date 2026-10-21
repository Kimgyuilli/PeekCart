package com.peekcart.global.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.DefaultLockingTaskExecutor;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * D-075 opt-in local analysis. 300 records are a bounded fixture, not a throughput target.
 * Actual poller/repository/producer are used; app background writers remain disabled.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "app.scheduling.enabled=false",
        "app.kafka.listener.enabled=false"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@EnabledIfEnvironmentVariable(named = "PEEKCART_OUTBOX_BENCHMARK", matches = "true")
@DisplayName("D-075 로컬 outbox 병목 분해 (명시 실행 전용)")
class OutboxThroughputLocalProbeTest extends AbstractIntegrationTest {
    private static final int RECORDS = 300;
    private static final int REPETITIONS = 3;
    private static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration CYCLE_TIMEOUT = Duration.ofMinutes(4);
    private static final String PADDING = "x".repeat(900);

    @Autowired OutboxEventRepository repository;
    @Autowired OutboxEventJpaRepository jpaRepository;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired LockProvider lockProvider;
    @Autowired ObjectMapper mapper;

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void measureLocalPublicationComponents() throws Exception {
        cleanDatabase();
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        SchedulerLock annotation = OutboxPollingScheduler.class.getMethod("pollAndPublish")
                .getAnnotation(SchedulerLock.class);
        assertThat(annotation.lockAtLeastFor()).isEqualTo("PT4S");
        assertThat(annotation.lockAtMostFor()).isEqualTo("PT5M");
        ProducerConfig configuration = new ProducerConfig(kafka.getProducerFactory().getConfigurationProperties());
        assertThat(configuration.getBoolean("enable.idempotence")).isTrue();
        assertThat(configuration.getString("acks")).isIn("all", "-1");
        Map<String, Object> producer = new LinkedHashMap<>();
        for (String key : List.of("acks", "enable.idempotence", "linger.ms", "batch.size", "compression.type",
                "max.in.flight.requests.per.connection", "delivery.timeout.ms", "request.timeout.ms", "max.block.ms")) {
            producer.put(key, configuration.values().get(key));
        }
        emit(Map.of("kind", "environment", "producer", producer, "records", RECORDS,
                "repetitions", REPETITIONS, "payloadPaddingBytes", PADDING.length(), "keys", 32,
                "mysqlImage", "mysql:8.0.46", "kafkaImage", "apache/kafka:3.8.1",
                "singleBroker", true, "backgroundWriters", false));

        for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
            // Rotate profile order, keeping 3 repetitions without always warming the same profile last.
            List<Profile> profiles = List.of(new Profile("baseline", 100, 5000, 4000),
                    new Profile("delay-only", 100, 500, 4000),
                    new Profile("larger-batch", 500, 500, 4000),
                    new Profile("no-min-lock-control", 100, 500, 0));
            for (int i = 0; i < profiles.size(); i++) {
                scheduledTrial(profiles.get((i + repetition - 1) % profiles.size()), repetition);
            }
            producerTrial(false, repetition);
            producerTrial(true, repetition);
        }
        saveTimingControl();
        brokerVerifierNegativeControl();
    }

    private void scheduledTrial(Profile profile, int repetition) throws Exception {
        jpaRepository.deleteAll();
        String topic = topic("poll");
        warm(topic);
        // Prime the actual JPA + poller path outside the measurement window as well.
        List<OutboxEvent> warmup = seed(topic, 20);
        MeasuredRepository measured = new MeasuredRepository(repository, 0);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            long warmStart = System.nanoTime();
            poller(measured, registry, 100).pollAndPublish();
            emit(Map.of("kind", "poller-warmup", "topic", topic, "records", 20,
                    "wallNs", System.nanoTime() - warmStart, "findNs", measured.findNs,
                    "saveNs", measured.saveNs, "publishTimerNs", registry.get("outbox.publish")
                            .tag("result", "success").timer().totalTime(TimeUnit.NANOSECONDS)));
        }
        finally { registry.close(); }
        assertThat(jpaRepository.countByStatus(OutboxEventStatus.PUBLISHED)).isEqualTo(20);
        jpaRepository.deleteAll();
        List<OutboxEvent> expected = seed(topic, RECORDS);
        measured = new MeasuredRepository(repository, 0);
        MeasuredRepository timed = measured;
        registry = new SimpleMeterRegistry();
        SimpleMeterRegistry trialRegistry = registry;
        try {
            OutboxPollingService poller = poller(timed, registry, profile.batch());
            DefaultLockingTaskExecutor executor = new DefaultLockingTaskExecutor(lockProvider);
            ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
            scheduler.setPoolSize(1);
            scheduler.setThreadNamePrefix("d075-local-");
            scheduler.setWaitForTasksToCompleteOnShutdown(true);
            scheduler.setAwaitTerminationSeconds(10);
            scheduler.initialize();
            CountDownLatch drained = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            List<Map<String, Object>> attempts = new ArrayList<>();
            String lockName = "d075-" + UUID.randomUUID();
            long start = System.nanoTime();
            String startInstant = Instant.now().toString();
            var future = scheduler.scheduleWithFixedDelay(() -> {
                long attemptStart = System.nanoTime();
                boolean[] executed = {false};
                long[] body = {0, 0};
                try {
                    executor.executeWithLock((Runnable) () -> {
                        executed[0] = true;
                        body[0] = System.nanoTime();
                        poller.pollAndPublish();
                        body[1] = System.nanoTime();
                    }, new LockConfiguration(Instant.now(), lockName, Duration.ofMinutes(5),
                            Duration.ofMillis(profile.minLockMs())));
                    long end = System.nanoTime();
                    attempts.add(Map.of("startNs", attemptStart - start, "endNs", end - start,
                            "executed", executed[0], "bodyStartNs", body[0] == 0 ? 0 : body[0] - start,
                            "bodyEndNs", body[1] == 0 ? 0 : body[1] - start));
                    if (trialRegistry.get("outbox.publish").tag("result", "failure").timer().count() > 0) {
                        throw new IllegalStateException("Publication failure; not a valid throughput sample");
                    }
                    if (timed.publishedSaves == RECORDS) drained.countDown();
                } catch (Throwable error) {
                    failure.set(error);
                    drained.countDown();
                }
            }, Duration.ofMillis(profile.delayMs()));
            boolean complete = false;
            try {
                complete = drained.await(90, TimeUnit.SECONDS);
            } finally {
                future.cancel(!complete);
                scheduler.shutdown();
            }
            long end = System.nanoTime();
            assertThat(failure.get()).isNull();
            assertThat(complete).as("bounded fixture drained").isTrue();
            assertThat(timed.publishedSaves).isEqualTo(RECORDS);
            assertThat(jpaRepository.countByStatus(OutboxEventStatus.PENDING)).isZero();
            assertThat(jpaRepository.countByStatus(OutboxEventStatus.FAILED)).isZero();
            assertThat(jpaRepository.countByStatus(OutboxEventStatus.PUBLISHED)).isEqualTo(RECORDS);
            List<OutboxEvent> all = new ArrayList<>(warmup);
            all.addAll(expected);
            verifyBroker(topic, all);
            long bodyNs = attempts.stream().filter(a -> (boolean) a.get("executed"))
                    .mapToLong(a -> (long) a.get("bodyEndNs") - (long) a.get("bodyStartNs")).sum();
            long attemptNs = attempts.stream().mapToLong(a -> (long) a.get("endNs") - (long) a.get("startNs")).sum();
            double publishNs = registry.get("outbox.publish").tag("result", "success").timer()
                    .totalTime(TimeUnit.NANOSECONDS);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", "scheduled"); row.put("profile", profile.name()); row.put("repetition", repetition);
            row.put("startInstant", startInstant); row.put("batch", profile.batch());
            row.put("delayMs", profile.delayMs()); row.put("minLockMs", profile.minLockMs());
            row.put("records", RECORDS); row.put("wallNs", end - start); row.put("pollBodyNs", bodyNs);
            row.put("attemptNs", attemptNs); row.put("findNs", timed.findNs); row.put("saveNs", timed.saveNs);
            row.put("findCalls", timed.findCalls); row.put("saveCalls", timed.saveCalls);
            row.put("publishTimerNs", publishNs); row.put("publishTimerCount", registry.get("outbox.publish")
                    .tag("result", "success").timer().count());
            row.put("attempts", attempts); row.put("brokerVerified", true); row.put("dbVerified", true);
            emit(row);
        } finally { registry.close(); }
    }

    private void producerTrial(boolean inFlight, int repetition) throws Exception {
        String topic = topic("producer");
        warm(topic);
        List<OutboxEvent> expected = events(topic, RECORDS);
        long start = System.nanoTime();
        List<CompletableFuture<?>> pending = new ArrayList<>();
        int maximum = inFlight ? 32 : 1;
        for (OutboxEvent event : expected) {
            pending.add(kafka.send(topic, event.getAggregateId(), event.getPayload()));
            if (pending.size() == maximum) {
                for (var send : pending) send.get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                pending.clear();
            }
        }
        for (var send : pending) send.get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        long end = System.nanoTime();
        verifyBroker(topic, expected);
        emit(Map.of("kind", "producer-only", "repetition", repetition, "window", maximum,
                "records", RECORDS, "wallNs", end - start, "brokerVerified", true));
    }

    private void saveTimingControl() throws Exception {
        jpaRepository.deleteAll();
        String topic = topic("control");
        warm(topic);
        List<OutboxEvent> expected = seed(topic, 30);
        MeasuredRepository delayed = new MeasuredRepository(repository, 5);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try {
            poller(delayed, registry, 100).pollAndPublish();
            assertThat(delayed.publishedSaves).isEqualTo(30);
            assertThat(delayed.saveNs).isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(150));
            assertThat(jpaRepository.countByStatus(OutboxEventStatus.PUBLISHED)).isEqualTo(30);
            verifyBroker(topic, expected);
            emit(Map.of("kind", "save-delay-control", "records", 30, "injectedPerSaveMs", 5,
                    "saveNs", delayed.saveNs, "saveCalls", delayed.saveCalls, "brokerVerified", true));
        } finally { registry.close(); }
    }

    private void brokerVerifierNegativeControl() throws Exception {
        String topic = topic("negative");
        warm(topic);
        List<OutboxEvent> expected = events(topic, 1);
        for (int i = 0; i < 2; i++) kafka.send(topic, expected.get(0).getAggregateId(), expected.get(0).getPayload())
                .get(PUBLISH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        assertThatThrownBy(() -> verifyBroker(topic, expected)).isInstanceOf(AssertionError.class);
        emit(Map.of("kind", "duplicate-negative-control", "rejected", true));
    }

    private OutboxPollingService poller(MeasuredRepository timed, SimpleMeterRegistry registry, int batch) {
        return new OutboxPollingService(timed, kafka, message -> { }, mapper, registry, batch, 5,
                PUBLISH_TIMEOUT, CYCLE_TIMEOUT);
    }

    private List<OutboxEvent> seed(String topic, int count) {
        return jpaRepository.saveAll(events(topic, count));
    }

    private List<OutboxEvent> events(String topic, int count) {
        List<OutboxEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int sequence = i;
            events.add(OutboxEvent.create("LOCAL_PROBE", "key-" + i % 32, topic, null, null,
                    id -> "{\"eventId\":\"" + id + "\",\"sequence\":" + sequence + ",\"padding\":\"" + PADDING + "\"}"));
        }
        return events;
    }

    private String topic(String label) throws Exception {
        String topic = "d075-" + label + "-" + UUID.randomUUID().toString().substring(0, 8);
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", SharedContainers.KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(20, TimeUnit.SECONDS);
        }
        return topic;
    }

    private void warm(String topic) throws Exception {
        long start = System.nanoTime();
        kafka.send(topic, "warmup", "warmup").get(30, TimeUnit.SECONDS);
        emit(Map.of("kind", "warmup", "topic", topic, "wallNs", System.nanoTime() - start));
    }

    private void verifyBroker(String topic, List<OutboxEvent> expected) {
        Map<String, String> payloads = new LinkedHashMap<>();
        Map<String, String> keys = new LinkedHashMap<>();
        for (OutboxEvent event : expected) {
            payloads.put(event.getEventId(), event.getPayload());
            keys.put(event.getEventId(), event.getAggregateId());
        }
        Map<String, List<String>> expectedKeyOrder = new LinkedHashMap<>();
        for (OutboxEvent event : expected) {
            expectedKeyOrder.computeIfAbsent(event.getAggregateId(), ignored -> new ArrayList<>()).add(event.getEventId());
        }
        Map<String, List<String>> actualKeyOrder = new LinkedHashMap<>();
        Map<String, Integer> occurrences = new LinkedHashMap<>();
        Map<String, Object> properties = Map.of(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                SharedContainers.KAFKA.getBootstrapServers(), ConsumerConfig.GROUP_ID_CONFIG, "d075-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties)) {
            List<TopicPartition> partitions = List.of(new TopicPartition(topic, 0), new TopicPartition(topic, 1),
                    new TopicPartition(topic, 2));
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            assertThat(ends.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(expected.size() + 1L);
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (System.nanoTime() < deadline) {
                for (var record : consumer.poll(Duration.ofMillis(200))) {
                    if ("warmup".equals(record.key()) && "warmup".equals(record.value())) continue;
                    String id;
                    try { id = mapper.readTree(record.value()).get("eventId").asText(); }
                    catch (Exception malformed) { throw new AssertionError("Malformed probe payload", malformed); }
                    assertThat(record.value()).isEqualTo(payloads.get(id));
                    assertThat(record.key()).isEqualTo(keys.get(id));
                    occurrences.merge(id, 1, Integer::sum);
                    actualKeyOrder.computeIfAbsent(record.key(), ignored -> new ArrayList<>()).add(id);
                }
                if (partitions.stream().allMatch(p -> consumer.position(p) >= ends.get(p))) break;
            }
            assertThat(occurrences).hasSize(expected.size());
            assertThat(occurrences.values()).allMatch(n -> n == 1);
            assertThat(actualKeyOrder).isEqualTo(expectedKeyOrder);
        }
    }

    private void emit(Map<String, ?> row) throws Exception {
        System.out.println("D075_RESULT " + mapper.writeValueAsString(row));
    }

    private record Profile(String name, int batch, int delayMs, int minLockMs) { }

    private static class MeasuredRepository implements OutboxEventRepository {
        private final OutboxEventRepository delegate;
        private final long delayMs;
        long findNs, saveNs, findCalls, saveCalls, publishedSaves;
        MeasuredRepository(OutboxEventRepository delegate, long delayMs) {
            this.delegate = delegate; this.delayMs = delayMs;
        }
        @Override public OutboxEvent save(OutboxEvent event) {
            long start = System.nanoTime();
            try {
                if (delayMs > 0) TimeUnit.MILLISECONDS.sleep(delayMs);
                OutboxEvent saved = delegate.save(event);
                if (saved.getStatus() == OutboxEventStatus.PUBLISHED) publishedSaves++;
                return saved;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            } finally { saveNs += System.nanoTime() - start; saveCalls++; }
        }
        @Override public List<OutboxEvent> findPendingEvents(int limit) {
            long start = System.nanoTime();
            try { return delegate.findPendingEvents(limit); }
            finally { findNs += System.nanoTime() - start; findCalls++; }
        }
        @Override public long countByStatus(OutboxEventStatus status) { return delegate.countByStatus(status); }
    }
}
