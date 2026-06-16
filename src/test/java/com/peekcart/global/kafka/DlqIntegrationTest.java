package com.peekcart.global.kafka;

import com.peekcart.global.port.SlackPort;
import com.peekcart.support.AbstractIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
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
@TestPropertySource(properties = "spring.task.scheduling.pool.size=1")
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

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
