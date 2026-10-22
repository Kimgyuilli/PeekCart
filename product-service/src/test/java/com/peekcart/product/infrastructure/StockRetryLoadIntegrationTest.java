package com.peekcart.product.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.FixedSequenceBackOff;
import com.peekcart.global.kafka.JitteredSequenceBackOff;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.infrastructure.kafka.StockReservationConsumer;
import com.peekcart.product.infrastructure.metrics.ProductKafkaRetryMetrics;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import com.peekcart.support.IntegrationTestConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/** Finite controlled contention, not a sustained load or production sizing benchmark. */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"
})
@org.springframework.context.annotation.Import({IntegrationTestConfig.class, SharedContainers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class StockRetryLoadIntegrationTest extends AbstractIntegrationTest {
    enum Mode { FIXED, JITTER }

    private static final String TOPIC = "order.created";
    private static final String DLQ = TOPIC + ".dlq";
    private static final String GROUP = "product-svc-order-created-group";
    private static final int STOCK = 100;
    private static final Duration DEADLINE = Duration.ofSeconds(90);
    private static final TopicPartition DLQ_PARTITION = new TopicPartition(DLQ, 0);

    @Autowired KafkaListenerEndpointRegistry registry;
    @Autowired KafkaTemplate<String, String> template;
    @Autowired KafkaAdmin kafkaAdmin;
    @Autowired @Qualifier("kafkaErrorHandler") CommonErrorHandler commonErrorHandler;
    @Autowired ObjectMapper mapper;
    @Autowired MeterRegistry meterRegistry;
    @MockitoSpyBean InventoryRepository inventoryRepository;
    @MockitoSpyBean StockReservationConsumer consumer;

    private ConcurrentMessageListenerContainer<?, ?> listener;
    private DefaultErrorHandler handler;
    private BackOff configured;
    private List<RetryListener> productionListeners;
    private double failedBefore;
    private double recoveredBefore;
    private KafkaConsumer<String, String> observer;
    private final ThreadLocal<String> currentEvent = new ThreadLocal<>();
    private final Map<String, AtomicInteger> deliveries = new ConcurrentHashMap<>();
    private final Set<String> firstReads = ConcurrentHashMap.newKeySet();
    private final Map<String, Integer> readVersions = new ConcurrentHashMap<>();
    private final Set<Long> arrivalThreads = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedQueue<Failure> failures = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Long> delays = new ConcurrentLinkedQueue<>();
    private final List<ConsumerRecord<String, String>> dlqRecords = new ArrayList<>();
    private final AtomicInteger generations = new AtomicInteger();
    private volatile CyclicBarrier gate;
    private volatile boolean persistent;
    private long started;
    private Long productId;
    private String run;
    private Mode mode;

    record Failure(String key, int attempt, boolean optimistic, long nanos) { }

    @BeforeEach
    void prepare() throws Exception {
        cleanDatabase();
        started = System.nanoTime();
        run = "retry-" + UUID.randomUUID();
        kafkaAdmin.createOrModifyTopics(TopicBuilder.name(TOPIC).partitions(3).replicas(1).build(),
                TopicBuilder.name(DLQ).partitions(1).replicas(1).build());
        cleanKafkaTopics(SharedContainers.KAFKA.getBootstrapServers(), TOPIC, DLQ);
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            Category category = Category.create("retry fixture", null);
            em.persist(category);
            Product product = Product.create(category, "retry fixture", "finite", 10_000, null);
            em.persist(product);
            em.persist(Inventory.create(product, STOCK));
            em.getTransaction().commit();
            productId = product.getId();
        } finally {
            em.close();
        }
        handler = (DefaultErrorHandler) commonErrorHandler;
        configured = (BackOff) ReflectionTestUtils.getField(
                ReflectionTestUtils.getField(handler, "failureTracker"), "backOff");
        assertThat(configured).isInstanceOf(JitteredSequenceBackOff.class);
        assertThat(ReflectionTestUtils.getField(configured, "jitterRatio")).isEqualTo(0.5);
        assertThat((long[]) ReflectionTestUtils.getField(configured, "intervals"))
                .containsExactly(1_000, 5_000, 30_000);
        List<RetryListener> registered = ReflectionTestUtils.invokeMethod(handler, "getRetryListeners");
        productionListeners = List.copyOf(registered);
        // D-078 ⑤: 운영 배선이 재시도 메트릭을 등록했는지 — 테스트 listener 는 그 옆에 덧붙인다.
        assertThat(productionListeners).hasSize(1).first().isInstanceOf(ProductKafkaRetryMetrics.class);
        failedBefore = count("kafka.listener.delivery.failed", "optimistic_lock");
        recoveredBefore = count("kafka.listener.recovered", null);
        handler.setRetryListeners(productionListeners.get(0), new RetryListener() {
            @Override
            public void failedDelivery(ConsumerRecord<?, ?> record, Exception exception, int attempt) {
                boolean optimistic = optimistic(exception);
                failures.add(new Failure(record.key().toString(), attempt, optimistic, System.nanoTime()));
                row("failure coordinate=" + record.topic() + "/" + record.partition() + "/"
                        + record.offset() + " key=" + record.key() + " attempt=" + attempt
                        + " optimistic=" + optimistic + " type=" + exception.getClass().getName());
                if (!optimistic && attempt == 1) exception.printStackTrace(System.out);
            }
        });
        doAnswer(invocation -> {
            String event = mapper.readTree((String) invocation.getArgument(0)).get("eventId").asText();
            int attempt = deliveries.computeIfAbsent(event, key -> new AtomicInteger()).incrementAndGet();
            currentEvent.set(event);
            row("delivery event=" + event + " attempt=" + attempt);
            try {
                return invocation.callRealMethod();
            } finally {
                currentEvent.remove();
            }
        }).when(consumer).handleOrderCreated(anyString());
        doAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Optional<Inventory> result = (Optional<Inventory>) invocation.callRealMethod();
            String event = currentEvent.get();
            if (event != null) {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                if (persistent) {
                    throw new ObjectOptimisticLockingFailureException(Inventory.class, productId);
                }
                if (gate != null && firstReads.add(event)) {
                    readVersions.put(event, result.orElseThrow().getVersion());
                    arrivalThreads.add(Thread.currentThread().getId());
                    row("arrival event=" + event + " barrier=" + System.identityHashCode(gate)
                            + " thread=" + Thread.currentThread().getName()
                            + " version=" + result.orElseThrow().getVersion());
                    gate.await(15, TimeUnit.SECONDS);
                }
            }
            return result;
        }).when(inventoryRepository).findByProductId(anyLong());

        observer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SharedContainers.KAFKA.getBootstrapServers(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000));
        observer.assign(List.of(DLQ_PARTITION));
        observer.seekToEnd(List.of(DLQ_PARTITION));
        observer.position(DLQ_PARTITION);
        var selected = registry.getListenerContainers().stream()
                .filter(c -> c.getContainerProperties().getTopics() != null)
                .filter(c -> Arrays.asList(c.getContainerProperties().getTopics()).contains(TOPIC)).toList();
        assertThat(selected).hasSize(1);
        listener = (ConcurrentMessageListenerContainer<?, ?>) selected.get(0);
        assertThat(listener.getCommonErrorHandler()).isSameAs(handler);
        assertThat(listener.isRunning()).isFalse();
        listener.setConcurrency(3);
    }

    private void start(Mode selected) {
        mode = selected;
        BackOff policy = mode == Mode.JITTER ? configured : new FixedSequenceBackOff(1_000, 5_000, 30_000);
        // Wrap only delay observation. The production BackOffHandler and its real sleeper are unchanged.
        handler.setBackOffFunction((record, exception) -> () -> {
            BackOffExecution execution = policy.start();
            return () -> {
                long delay = execution.nextBackOff();
                delays.add(delay);
                row("backoff key=" + record.key() + " delayMs=" + delay);
                return delay;
            };
        });
        listener.start();
        awaitListenerAssignment(List.of(listener));
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(listener.getAssignedPartitions()).hasSize(3);
            assertThat(listener.getContainers()).hasSize(3).allSatisfy(child ->
                    assertThat(child.getAssignedPartitions()).hasSize(1));
        });
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void threePartitionConflictConvergesAndDuplicateHasNoSecondEffect(Mode selected) throws Exception {
        gate = new CyclicBarrier(3, () -> {
            generations.incrementAndGet();
            row("generation completed participants=" + firstReads.size());
        });
        start(selected);
        List<RecordMetadata> sent = new ArrayList<>();
        List<String> payloads = new ArrayList<>();
        for (int partition = 0; partition < 3; partition++) {
            String payload = payload(partition);
            payloads.add(payload);
            sent.add(send(partition, run + "-" + partition, payload));
        }
        await().atMost(DEADLINE).untilAsserted(() -> assertEffects(3));
        awaitCommitted(sent);
        drainDlqToEnd();
        assertThat(generations.get()).isEqualTo(1);
        assertThat(firstReads).hasSize(3);
        assertThat(arrivalThreads).hasSize(3);
        assertThat(readVersions).hasSize(3).allSatisfy((event, version) -> assertThat(version).isZero());
        assertThat(failures).hasSizeGreaterThanOrEqualTo(2).allMatch(Failure::optimistic);
        assertThat(deliveries).hasSize(3).allSatisfy((event, count) ->
                assertThat(count.get()).isBetween(1, 4));
        assertThat(delays).isNotEmpty().doesNotContain(BackOffExecution.STOP);
        assertThat(dlqRecords).isEmpty();
        assertThat(count("kafka.listener.delivery.failed", "optimistic_lock") - failedBefore)
                .isEqualTo(failures.size());
        assertThat(count("kafka.listener.recovered", null) - recoveredBefore).isZero();
        // Same event IDs/order IDs after success: consume and commit, then inspect the DB again.
        List<RecordMetadata> duplicates = new ArrayList<>();
        for (int partition = 0; partition < 3; partition++) {
            duplicates.add(send(partition, run + "-" + partition, payloads.get(partition)));
        }
        awaitCommitted(duplicates);
        assertEffects(3);
        drainDlqToEnd();
        assertThat(dlqRecords).isEmpty();
        row("result transient sourceRecords=3 duplicateRecords=3 conflicts=" + failures.size()
                + " deliveries=" + deliveries + " finalStock=" + (STOCK - 3) + " dlq=0");
    }

    @ParameterizedTest
    @EnumSource(Mode.class)
    void persistentConflictExhaustsFourDeliveriesIntoOneDlqWithoutEffects(Mode selected) throws Exception {
        persistent = true;
        start(selected);
        String value = payload(0);
        RecordMetadata sent = send(0, run + "-0", value);
        awaitCommitted(List.of(sent));
        drainDlqToEnd();
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.values().iterator().next().get()).isEqualTo(4);
        assertThat(failures).hasSize(4).allMatch(Failure::optimistic);
        assertThat(failures.stream().map(Failure::attempt)).containsExactly(1, 2, 3, 4);
        List<Failure> attempts = new ArrayList<>(failures);
        List<Long> issued = new ArrayList<>(delays);
        assertThat(issued).hasSize(4);
        long[] base = {1_000, 5_000, 30_000};
        for (int i = 0; i < 3; i++) {
            if (mode == Mode.FIXED) assertThat(issued.get(i)).isEqualTo(base[i]);
            else assertThat(issued.get(i)).isBetween(base[i] / 2, base[i] * 3 / 2);
            // A substituted/no-op sleeper cannot satisfy these observed inter-attempt intervals.
            // Allow 10ms for the sleeper wall-clock granularity; do not change the retry policy.
            assertThat(attempts.get(i + 1).nanos() - attempts.get(i).nanos())
                    .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(issued.get(i) - 10));
        }
        assertThat(issued.get(3)).isEqualTo(BackOffExecution.STOP);
        assertThat(dlqRecords).hasSize(1);
        assertThat(count("kafka.listener.delivery.failed", "optimistic_lock") - failedBefore).isEqualTo(4);
        assertThat(count("kafka.listener.recovered", null) - recoveredBefore).isEqualTo(1);
        ConsumerRecord<String, String> dead = dlqRecords.get(0);
        assertThat(dead.key()).isEqualTo(run + "-0");
        assertThat(dead.value()).isEqualTo(value);
        assertThat(textHeader(dead, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
        assertThat(ByteBuffer.wrap(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_PARTITION).value()).getInt())
                .isEqualTo(sent.partition());
        assertThat(ByteBuffer.wrap(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET).value()).getLong())
                .isEqualTo(sent.offset());
        assertEffects(0);
        row("result persistent source=" + TOPIC + "/" + sent.partition() + "/" + sent.offset()
                + " deliveries=4 dlq=1 finalStock=" + STOCK + " delays=" + issued);
    }

    private String payload(int partition) {
        return "{\"eventId\":\"" + eventId(partition) + "\",\"payload\":{\"orderId\":"
                + (10_000 + partition) + ",\"items\":[{\"productId\":" + productId + ",\"quantity\":1}]}}";
    }

    private String eventId(int partition) {
        // Production event IDs are UUIDs; both processed_events and reservations allow 36 characters.
        return UUID.nameUUIDFromBytes((run + ":" + partition).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private RecordMetadata send(int partition, String key, String value) {
        RecordMetadata sent = template.send(new ProducerRecord<>(TOPIC, partition, key, value))
                .orTimeout(10, TimeUnit.SECONDS).join().getRecordMetadata();
        row("ack coordinate=" + sent.topic() + "/" + sent.partition() + "/" + sent.offset() + " key=" + key
                + " event=" + eventId(partition));
        return sent;
    }

    private void awaitCommitted(List<RecordMetadata> sent) throws Exception {
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            await().atMost(DEADLINE).untilAsserted(() -> {
                var offsets = admin.listConsumerGroupOffsets(GROUP).partitionsToOffsetAndMetadata()
                        .get(5, TimeUnit.SECONDS);
                for (RecordMetadata record : sent) {
                    var committed = offsets.get(new TopicPartition(record.topic(), record.partition()));
                    assertThat(committed).isNotNull();
                    assertThat(committed.offset()).isGreaterThan(record.offset());
                }
            });
        }
    }

    private void drainDlqToEnd() {
        long end = observer.endOffsets(List.of(DLQ_PARTITION)).get(DLQ_PARTITION);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (observer.position(DLQ_PARTITION) < end && System.nanoTime() < deadline) {
            observer.poll(Duration.ofMillis(100)).forEach(dlqRecords::add);
        }
        assertThat(observer.position(DLQ_PARTITION)).isGreaterThanOrEqualTo(end);
    }

    private void assertEffects(int successes) {
        EntityManager em = emf.createEntityManager();
        try {
            assertThat(((Number) em.createNativeQuery("SELECT stock FROM inventories WHERE product_id=:id")
                    .setParameter("id", productId).getSingleResult()).intValue()).isEqualTo(STOCK - successes);
            for (String table : List.of("stock_reservations", "outbox_events", "processed_events")) {
                assertThat(((Number) em.createNativeQuery("SELECT COUNT(*) FROM " + table).getSingleResult()).intValue())
                        .as(table).isEqualTo(successes);
            }
        } finally {
            em.close();
        }
    }

    private double count(String name, String cause) {
        var search = meterRegistry.find(name).tag("topic", TOPIC);
        if (cause != null) search = search.tag("cause", cause);
        Counter counter = search.counter();
        return counter == null ? 0 : counter.count();
    }

    private static String textHeader(ConsumerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static boolean optimistic(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException
                    || cause instanceof jakarta.persistence.OptimisticLockException
                    || cause instanceof org.hibernate.StaleStateException) return true;
        }
        return false;
    }

    private void row(String message) {
        String row = "D078_RETRY mode=" + mode + " run=" + run + " elapsedNanos="
                + (System.nanoTime() - started) + " " + message;
        System.out.println(row);
    }

    @AfterEach
    void cleanup() {
        // Stop/join the real consumers before resetting spy state or touching shared DB/topics.
        if (gate != null) gate.reset();
        if (listener != null) {
            listener.stop();
            assertThat(listener.isRunning()).isFalse();
        }
        if (handler != null && productionListeners != null) {
            handler.setBackOffFunction((record, exception) -> configured);
            handler.setRetryListeners(productionListeners.toArray(RetryListener[]::new));
        }
        if (observer != null) observer.close(Duration.ofSeconds(5));
        row("cleanup stopped=" + (listener == null || !listener.isRunning())
                + " failures=" + failures + " delays=" + delays);
        cleanDatabase();
        cleanKafkaTopics(SharedContainers.KAFKA.getBootstrapServers(), TOPIC, DLQ);
    }
}
