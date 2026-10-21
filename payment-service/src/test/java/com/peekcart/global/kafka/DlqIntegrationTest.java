package com.peekcart.global.kafka;

import com.peekcart.global.port.SlackPort;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.support.KafkaHeaders;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
// ADR-0029: 리스너는 테스트에서 기본 off 다. 실제 Kafka 왕복으로 재시도 소진 → DLQ 라우팅 → 원장 적재를 확인하는 것이 검증 대상이다.
@TestPropertySource(properties = {"spring.task.scheduling.pool.size=1", "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration", "app.kafka.listener.enabled=true", "logging.config=classpath:dlq-diagnostic-logback.xml"})
@Import({DlqIntegrationTest.TestConfig.class, SharedContainers.class})
// 켠 자율 writer 의 수명을 자기 클래스에 가둔다 (ADR-0029 D3) — context 가 캐시된 채 남으면
// 리스너가 이후 클래스의 공유 브로커·DB 를 계속 고친다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("DLQ 통합 테스트")
class DlqIntegrationTest extends AbstractIntegrationTest {

    @Autowired KafkaListenerEndpointRegistry listenerRegistry;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired DlqTestListener dlqTestListener;
    @Autowired com.peekcart.global.deadletter.DeadLetterRecordJpaRepository deadLetterRepository;

    private RecordMetadata published;

    @TestConfiguration
    static class TestConfig {
        static final AtomicInteger slackCallCount = new AtomicInteger(0);

        @Bean
        SlackPort slackPort() {
            return message -> slackCallCount.incrementAndGet();
        }

        @Bean
        @Primary
        CommonErrorHandler testKafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate, SlackPort slackPort) {
            DeadLetterPublishingRecoverer dlqRecoverer = new DeadLetterPublishingRecoverer(
                    kafkaTemplate,
                    (record, ex) -> new TopicPartition(record.topic() + ".dlq", -1)
            );
            dlqRecoverer.setFailIfSendResultIsError(true);

            return new DefaultErrorHandler((record, exception) -> {
                diagnostic("recoverer-start topic=" + record.topic() + " partition=" + record.partition()
                        + " offset=" + record.offset() + " key=" + record.key()
                        + " cause=" + exception.getClass().getName());
                try {
                    dlqRecoverer.accept(record, exception);
                    diagnostic("recoverer-returned key=" + record.key());
                } catch (RuntimeException failure) {
                    diagnostic("recoverer-failed key=" + record.key() + " type=" + failure.getClass().getName());
                    throw failure;
                }
                try {
                    slackPort.send(String.format("[DLQ] topic=%s", record.topic()));
                } catch (Exception e) {
                    // ignore
                }
            }, new FixedSequenceBackOff(100, 100, 100));
        }

        @Bean
        DlqTestListener dlqTestListener() {
            return new DlqTestListener();
        }
    }

    static class DlqTestListener {
        final BlockingQueue<ConsumerRecord<String, String>> records = new LinkedBlockingQueue<>();

        @KafkaListener(
                topics = {"order.created.dlq", "payment.completed.dlq",
                        "payment.failed.dlq", "order.cancelled.dlq"},
                groupId = "test-dlq-verification-group"
        )
        public void handle(ConsumerRecord<String, String> record) {
            records.add(record);
            diagnostic("observer-received topic=" + record.topic() + " partition=" + record.partition()
                    + " offset=" + record.offset() + " key=" + record.key());
        }
    }

    @BeforeEach
    void setUp(TestInfo testInfo) {
        published = null;
        diagnostic("setup-start test=" + testInfo.getTestMethod().orElseThrow().getName());
        snapshotAssignments();
        awaitListenerAssignment(listenerRegistry);
        diagnostic("assignment-wait-returned");
        snapshotAssignments();
        MDC.clear();
        dlqTestListener.records.clear();
        TestConfig.slackCallCount.set(0);
        // 원장 행을 남기면 다음 테스트의 await 가 기존 행을 보고 즉시 끝난다 (④-c-2b-3b P17).
        deadLetterRepository.deleteAll();
        diagnostic("setup-cleared queue=" + dlqTestListener.records.size());
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("Consumer 처리 실패 시 재시도 소진 후 DLQ 토픽으로 라우팅되고 Slack 알림이 발송된다")
    void consumerFailure_routesToDlqAndSendsSlack() {
        // given: 파싱 불가능한 잘못된 메시지
        String invalidMessage = "invalid-json-message";
        // 브로커를 클래스 간에 공유하므로(ADR-0028) 앞 클래스가 *.dlq 에 남긴 레코드가 이 리스너에 늦게 도착할 수
        // 있다 — @BeforeEach 의 clear() 뒤에도. 고유 key 로 이 테스트가 만든 레코드만 고른다.
        String uniqueKey = "route-key-" + java.util.UUID.randomUUID();

        // when: order.created 토픽에 전송 → 이를 소비하는 각 consumer group 실패
        //   (PaymentEventConsumer 결제 생성 + StockReservationConsumer 재고 예약, ADR-0012 D3)
        published = kafkaTemplate.send("order.created", uniqueKey, invalidMessage)
                .orTimeout(10, TimeUnit.SECONDS).join().getRecordMetadata();
        diagnostic("producer-ack key=" + uniqueKey + " coordinate=" + published);

        // then: 각 group 재시도 소진 → order.created.dlq 로 라우팅(consumer 당 1건) + Slack 발송
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(dlqTestListener.records).anySatisfy(r -> assertThat(r.key()).isEqualTo(uniqueKey));
            assertThat(TestConfig.slackCallCount.get()).isGreaterThanOrEqualTo(1);
        });

        // DLQ 메시지 검증: 원본 메시지 보존 + 토픽 확인 (consumer 수와 무관하게 모든 DLQ record 가 동일 규약)
        assertThat(dlqTestListener.records).filteredOn(r -> uniqueKey.equals(r.key())).allSatisfy(record -> {
            assertThat(record.topic()).isEqualTo("order.created.dlq");
            assertThat(record.value()).isEqualTo(invalidMessage);
        });
    }

    @Test
    @DisplayName("DLQ 라우팅 시 X-Trace-Id / X-User-Id 헤더가 보존된다 (D-010)")
    void dlqPreservesTraceHeaders() {
        // given: ProducerRecord 직접 생성 + KafkaTraceHeaders 부착 (Outbox 발행 경로 모방)
        // MDC.put 만으로는 Kafka 헤더가 자동 생성되지 않으므로 헤더를 명시적으로 부착해야 한다.
        // 고유 key — 공유 브로커의 앞 클래스 레코드가 섞이지 않게 한다 (위 케이스와 같은 이유).
        String uniqueKey = "trace-key-" + java.util.UUID.randomUUID();
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "order.created", null, uniqueKey, "invalid-json-message");
        record.headers().add(KafkaTraceHeaders.TRACE_ID,
                "trace-dlq-001".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaTraceHeaders.USER_ID,
                "77".getBytes(StandardCharsets.UTF_8));

        // when
        published = kafkaTemplate.send(record).orTimeout(10, TimeUnit.SECONDS).join().getRecordMetadata();
        diagnostic("producer-ack key=" + uniqueKey + " coordinate=" + published);

        // then: DLQ 토픽의 record 가 원본 헤더 보존 (DeadLetterPublishingRecoverer 가 헤더 자동 복사)
        //   order.created 는 다중 consumer group 이 소비하므로 DLQ record 가 1건 이상일 수 있다.
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(dlqTestListener.records).anySatisfy(r -> assertThat(r.key()).isEqualTo(uniqueKey));
        });

        assertThat(dlqTestListener.records).filteredOn(r -> uniqueKey.equals(r.key())).allSatisfy(dlqRecord -> {
            assertThat(headerValue(dlqRecord, KafkaTraceHeaders.TRACE_ID))
                    .isEqualTo("trace-dlq-001");
            assertThat(headerValue(dlqRecord, KafkaTraceHeaders.USER_ID))
                    .isEqualTo("77");
        });
    }

    @Test
    @DisplayName("DLT 의 original timestamp 가 원장 original_timestamp 로 정확히 저장된다 (④-c-2b-3b P17)")
    void dlqLedgerPersistsOriginalTimestampExactly() {
        // **non-null 단언으로는 부족하다** — 재발행 시각이나 엉뚱한 헤더 값을 저장해도 green 이 된다.
        // 그래서 실행마다 한 번 고정한 timestamp 로 발행하고 **그 값과 정확히 같은지**를 본다.
        //
        // 이 값은 replay 상관의 fingerprint 축(④-c-2b-3b P15-b 축 8)이다. 조용히 NULL 이 되거나
        // 재발행 시각으로 덮이면 **모든 상관이 실패해 사건이 갈라진다** — backlog=1 계약이 그 자리에서 깨진다.
        // (NULL 비율 측정은 P22 소관이고, 여기서 고정하는 것은 계약이다.)
        // 과거 날짜 리터럴은 broker retention에 의해 소비/재시도 전에 삭제될 수 있다.
        // 보존기간 안의 1분 전 값을 한 번 고정해 재발행 시각과 구분한다. 대기/정확값 검증은 그대로다.
        long fixedTimestamp = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(1);
        String uniqueKey = "ts-key-" + java.util.UUID.randomUUID();
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "order.created", null, fixedTimestamp, uniqueKey, "invalid-json-message");

        published = kafkaTemplate.send(record).orTimeout(10, TimeUnit.SECONDS).join().getRecordMetadata();
        diagnostic("producer-ack key=" + uniqueKey + " coordinate=" + published);

        // **DLT 헤더와 원장 양쪽을 본다.** 원장만 보면 "그 값이 DLT 에서 온 것" 이 증명되지 않는다 —
        // 재발행 시각을 넣어도, 다른 경로로 같은 값이 들어와도 통과한다.
        AtomicBoolean ledgerAssertionReached = new AtomicBoolean();
        try {
            await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
                assertThat(dlqTestListener.records)
                        .anySatisfy(r -> assertThat(r.key()).isEqualTo(uniqueKey));
                ledgerAssertionReached.set(true);
                assertThat(deadLetterRepository.findAll())
                        .anySatisfy(row -> assertThat(row.getOriginalKey()).isEqualTo(uniqueKey));
            });
        } catch (RuntimeException | AssertionError failure) {
            diagnostic("await-failed type=" + failure.getClass().getName()
                    + " queue=" + dlqTestListener.records.size()
                    + " keyMatches=" + dlqTestListener.records.stream().filter(r -> uniqueKey.equals(r.key())).count()
                    + " ledgerAssertionReached=" + ledgerAssertionReached.get());
            // Diagnostics follow the original 20s failure; they cannot replace its assertion or extend its budget.
            try {
                snapshotAssignments();
                snapshotBrokerOffsets();
            } catch (RuntimeException diagnosticFailure) {
                diagnostic("inspection-failed type=" + diagnosticFailure.getClass().getName());
            }
            throw failure;
        }

        ConsumerRecord<String, String> dlt = dlqTestListener.records.stream()
                .filter(r -> uniqueKey.equals(r.key()))
                .findFirst().orElseThrow();
        assertThat(headerLong(dlt, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP)).isEqualTo(fixedTimestamp);

        // **고유 key 로 이 테스트가 만든 행만 고른다** — 같은 클래스의 다른 테스트가 남긴 행이 섞이면
        // allSatisfy 가 엉뚱한 이유로 실패하거나, 기존 행 때문에 await 가 즉시 끝난다.
        assertThat(deadLetterRepository.findAll())
                .filteredOn(row -> uniqueKey.equals(row.getOriginalKey()))
                .isNotEmpty()
                .allSatisfy(row -> assertThat(row.getOriginalTimestamp()).isEqualTo(fixedTimestamp));
    }

    private static void diagnostic(String message) {
        System.err.println("DLQ-DIAGNOSTIC " + Instant.now() + " [" + Thread.currentThread().getName() + "] " + message);
    }

    private void snapshotAssignments() {
        listenerRegistry.getListenerContainers().forEach(container -> diagnostic(
                "container id=" + container.getListenerId() + " group=" + container.getGroupId()
                        + " running=" + container.isRunning() + " assigned=" + container.getAssignedPartitions()));
    }

    /** Independent Admin reads only; never access a listener's KafkaConsumer from this test thread. */
    private void snapshotBrokerOffsets() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        Admin admin = null;
        try {
            admin = Admin.create(Map.of(
                    "bootstrap.servers", SharedContainers.KAFKA.getBootstrapServers(),
                    "request.timeout.ms", "1000", "default.api.timeout.ms", "1000"));
            Map<TopicPartition, OffsetSpec> earliest = new LinkedHashMap<>();
            Map<TopicPartition, OffsetSpec> latest = new LinkedHashMap<>();
            listenerRegistry.getListenerContainers().forEach(container -> {
                var assigned = container.getAssignedPartitions();
                if (assigned != null) assigned.forEach(partition -> {
                    earliest.put(partition, OffsetSpec.earliest());
                    latest.put(partition, OffsetSpec.latest());
                });
            });
            if (published != null) {
                TopicPartition original = new TopicPartition(published.topic(), published.partition());
                earliest.put(original, OffsetSpec.earliest());
                latest.put(original, OffsetSpec.latest());
            }
            var starts = admin.listOffsets(earliest).all().get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            var ends = admin.listOffsets(latest).all().get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            starts.forEach((partition, start) -> diagnostic("broker-range partition=" + partition
                    + " start=" + start.offset() + " end=" + ends.get(partition).offset()));
            for (String group : java.util.List.of("test-dlq-verification-group", "payment-svc-order-created-group",
                    "payment-svc-dlq-group")) {
                var offsets = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata()
                        .get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
                offsets.forEach((partition, offset) -> diagnostic("committed group=" + group
                        + " partition=" + partition + " offset=" + offset.offset()));
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            diagnostic("broker-inspection-failed type=" + failure.getClass().getName());
        } finally {
            if (admin != null) {
                try {
                    admin.close(Duration.ofMillis(Math.min(250, Math.max(0,
                            TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())))));
                } catch (RuntimeException failure) {
                    diagnostic("broker-inspection-close-failed type=" + failure.getClass().getName());
                }
            }
        }
    }

    private static long remainingMillis(long deadline) throws java.util.concurrent.TimeoutException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new java.util.concurrent.TimeoutException("diagnostic deadline exhausted");
        return remaining;
    }

    /** DLT 헤더의 8-byte big-endian long. {@code DLT_ORIGINAL_TIMESTAMP} 는 문자열이 아니다. */
    private static Long headerLong(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : java.nio.ByteBuffer.wrap(header.value()).getLong();
    }

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
