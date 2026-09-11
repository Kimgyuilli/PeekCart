package com.peekcart.global.kafka;

import com.peekcart.global.port.SlackPort;
import com.peekcart.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.support.KafkaHeaders;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"spring.task.scheduling.pool.size=1", "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
@Import(DlqIntegrationTest.TestConfig.class)
@DisplayName("DLQ 통합 테스트")
class DlqIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired DlqTestListener dlqTestListener;
    @Autowired com.peekcart.global.deadletter.DeadLetterRecordJpaRepository deadLetterRepository;

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
                dlqRecoverer.accept(record, exception);
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
        }
    }

    @BeforeEach
    void setUp() {
        MDC.clear();
        dlqTestListener.records.clear();
        TestConfig.slackCallCount.set(0);
        // 원장 행을 남기면 다음 테스트의 await 가 기존 행을 보고 즉시 끝난다 (④-c-2b-3b P17).
        deadLetterRepository.deleteAll();
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

        // when: order.created 토픽에 전송 → 이를 소비하는 각 consumer group 실패
        //   (PaymentEventConsumer 결제 생성 + StockReservationConsumer 재고 예약, ADR-0012 D3)
        kafkaTemplate.send("order.created", "test-key", invalidMessage);

        // then: 각 group 재시도 소진 → order.created.dlq 로 라우팅(consumer 당 1건) + Slack 발송
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(dlqTestListener.records).isNotEmpty();
            assertThat(TestConfig.slackCallCount.get()).isGreaterThanOrEqualTo(1);
        });

        // DLQ 메시지 검증: 원본 메시지 보존 + 토픽 확인 (consumer 수와 무관하게 모든 DLQ record 가 동일 규약)
        assertThat(dlqTestListener.records).allSatisfy(record -> {
            assertThat(record.topic()).isEqualTo("order.created.dlq");
            assertThat(record.value()).isEqualTo(invalidMessage);
        });
    }

    @Test
    @DisplayName("DLQ 라우팅 시 X-Trace-Id / X-User-Id 헤더가 보존된다 (D-010)")
    void dlqPreservesTraceHeaders() {
        // given: ProducerRecord 직접 생성 + KafkaTraceHeaders 부착 (Outbox 발행 경로 모방)
        // MDC.put 만으로는 Kafka 헤더가 자동 생성되지 않으므로 헤더를 명시적으로 부착해야 한다.
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "order.created", null, "test-key", "invalid-json-message");
        record.headers().add(KafkaTraceHeaders.TRACE_ID,
                "trace-dlq-001".getBytes(StandardCharsets.UTF_8));
        record.headers().add(KafkaTraceHeaders.USER_ID,
                "77".getBytes(StandardCharsets.UTF_8));

        // when
        kafkaTemplate.send(record);

        // then: DLQ 토픽의 record 가 원본 헤더 보존 (DeadLetterPublishingRecoverer 가 헤더 자동 복사)
        //   order.created 는 다중 consumer group 이 소비하므로 DLQ record 가 1건 이상일 수 있다.
        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(dlqTestListener.records).isNotEmpty();
        });

        assertThat(dlqTestListener.records).allSatisfy(dlqRecord -> {
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
        // 그래서 고정 timestamp 를 실은 ProducerRecord 로 발행하고 **그 값과 정확히 같은지**를 본다.
        //
        // 이 값은 replay 상관의 fingerprint 축(④-c-2b-3b P15-b 축 8)이다. 조용히 NULL 이 되거나
        // 재발행 시각으로 덮이면 **모든 상관이 실패해 사건이 갈라진다** — backlog=1 계약이 그 자리에서 깨진다.
        // (NULL 비율 측정은 P22 소관이고, 여기서 고정하는 것은 계약이다.)
        long fixedTimestamp = 1_757_000_123_456L;
        String uniqueKey = "ts-key-" + java.util.UUID.randomUUID();
        ProducerRecord<String, String> record = new ProducerRecord<>(
                "order.created", null, fixedTimestamp, uniqueKey, "invalid-json-message");

        kafkaTemplate.send(record);

        // **DLT 헤더와 원장 양쪽을 본다.** 원장만 보면 "그 값이 DLT 에서 온 것" 이 증명되지 않는다 —
        // 재발행 시각을 넣어도, 다른 경로로 같은 값이 들어와도 통과한다.
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(dlqTestListener.records)
                    .anySatisfy(r -> assertThat(r.key()).isEqualTo(uniqueKey));
            assertThat(deadLetterRepository.findAll())
                    .anySatisfy(row -> assertThat(row.getOriginalKey()).isEqualTo(uniqueKey));
        });

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
