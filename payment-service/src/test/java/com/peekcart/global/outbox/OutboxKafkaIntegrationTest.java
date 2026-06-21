package com.peekcart.global.outbox;

import com.peekcart.global.kafka.KafkaTraceHeaders;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.infrastructure.outbox.PaymentOutboxEventPublisher;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Outbox → Kafka 발행 파이프라인 E2E 통합 테스트.
 *
 * <p>Order peel(PR-a) 이후 root 잔여 app 은 PAYMENT aggregateType 만 발행하므로(order 는 order-service 의
 * orderOutboxPollingJob 소유), 본 테스트는 {@code payment.completed} 를 프록시로 outbox→Kafka 발행 메커니즘
 * (PUBLISHED 전이·중복 발행 금지·trace 헤더 전파, D-010/D-013/D-019)을 검증한다. order.created→Payment 생성 등
 * 도메인 간 소비 플로우는 cross-service(order-service 발행 ↔ root payment 소비)라 각 서비스 consumer 테스트가 담당한다.</p>
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {"spring.task.scheduling.pool.size=1", "spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
@Import({IntegrationTestConfig.class, OutboxKafkaIntegrationTest.TraceCaptureConfig.class})
@DisplayName("Outbox → Kafka E2E 통합 테스트 (Order peel 후 payment-observable)")
class OutboxKafkaIntegrationTest extends AbstractIntegrationTest {

    @TestConfiguration
    static class TraceCaptureConfig {
        @Bean
        PaymentCompletedHeaderCapture paymentCompletedHeaderCapture() {
            return new PaymentCompletedHeaderCapture();
        }
    }

    static class PaymentCompletedHeaderCapture {
        final BlockingQueue<ConsumerRecord<String, String>> records = new LinkedBlockingQueue<>();

        @KafkaListener(topics = "payment.completed", groupId = "test-trace-header-verifier")
        public void capture(ConsumerRecord<String, String> record) {
            records.add(record);
        }
    }

    private static final Long USER_ID = 42L;
    private static final AtomicLong ORDER_ID_SEQ = new AtomicLong(1);

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

    @Autowired PaymentOutboxEventPublisher paymentOutboxEventPublisher;
    @Autowired OutboxPollingService outboxPollingService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired PaymentCompletedHeaderCapture headerCapture;

    @BeforeEach
    void setUp() {
        MDC.clear();
        headerCapture.records.clear();
        cleanDatabase();
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("payment.completed → Outbox 저장(PENDING) → Kafka 발행(PUBLISHED) → 테스트 listener 수신")
    void paymentCompleted_publish_e2e() {
        // given: in-memory Payment (outbox_events 는 payments/orders 에 FK 없음 → 영속 불요)
        Payment payment = newPayment();
        String aggregateKey = payment.getOrderId().toString();

        // when: Outbox 에 이벤트 저장
        paymentOutboxEventPublisher.publishPaymentCompleted(payment, USER_ID);
        List<OutboxEvent> pending = outboxEventRepository.findPendingEvents(100);
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).getStatus()).isEqualTo(OutboxEventStatus.PENDING);

        // when: 폴링 → Kafka 발행
        pollUntilPublished();

        // then: PENDING 비워짐 + Kafka 로 발행되어 테스트 listener 수신(key=orderId)
        assertThat(outboxEventRepository.findPendingEvents(100)).isEmpty();
        ConsumerRecord<String, String> record = awaitRecordWithKey(aggregateKey);
        assertThat(record.key()).isEqualTo(aggregateKey);
    }

    @Test
    @DisplayName("PUBLISHED 이벤트는 재폴링 시 중복 발행되지 않는다")
    void publishedEvent_notRePublished() {
        // given
        Payment payment = newPayment();
        String aggregateKey = payment.getOrderId().toString();
        paymentOutboxEventPublisher.publishPaymentCompleted(payment, USER_ID);
        pollUntilPublished();

        // 1회 발행되어 테스트 listener 에 도달
        awaitRecordWithKey(aggregateKey);
        assertThat(countRecordsWithKey(aggregateKey)).isEqualTo(1);

        // when: 재폴링
        outboxPollingService.pollAndPublish();

        // then: PENDING 없음 + 해당 key 발행 record 수 변화 없음 (중복 발행 금지)
        assertThat(outboxEventRepository.findPendingEvents(100)).isEmpty();
        await().during(2, TimeUnit.SECONDS).atMost(4, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(countRecordsWithKey(aggregateKey)).isEqualTo(1));
    }

    @Test
    @DisplayName("MDC traceId/userId set → ProducerRecord 헤더 X-Trace-Id / X-User-Id 전파 (D-010)")
    void traceHeaders_propagated_when_mdc_set() {
        // given
        MDC.put("traceId", "test-trace-001");
        MDC.put("userId", "42");
        Payment payment = newPayment();
        String aggregateKey = payment.getOrderId().toString();

        // when
        paymentOutboxEventPublisher.publishPaymentCompleted(payment, USER_ID);
        outboxPollingService.pollAndPublish();

        // then: 별도 consumer group 의 @KafkaListener 큐에서 본 테스트가 발행한 메시지(key=orderId)만 식별해 검증
        ConsumerRecord<String, String> record = awaitRecordWithKey(aggregateKey);
        assertThat(headerValue(record, KafkaTraceHeaders.TRACE_ID)).isEqualTo("test-trace-001");
        assertThat(headerValue(record, KafkaTraceHeaders.USER_ID)).isEqualTo("42");
    }

    @Test
    @DisplayName("MDC 미설정 → ProducerRecord 헤더 X-Trace-Id / X-User-Id 미주입 (빈 헤더 생성 금지)")
    void traceHeaders_absent_when_mdc_clear() {
        // given (MDC.clear 는 setUp 에서 보장)
        Payment payment = newPayment();
        String aggregateKey = payment.getOrderId().toString();

        // when
        paymentOutboxEventPublisher.publishPaymentCompleted(payment, USER_ID);
        outboxPollingService.pollAndPublish();

        // then
        ConsumerRecord<String, String> record = awaitRecordWithKey(aggregateKey);
        assertThat(record.headers().lastHeader(KafkaTraceHeaders.TRACE_ID)).isNull();
        assertThat(record.headers().lastHeader(KafkaTraceHeaders.USER_ID)).isNull();
    }

    // ── 헬퍼 메서드 ──

    /**
     * Outbox 이벤트가 PUBLISHED 로 전이될 때까지 폴링을 반복한다.
     *
     * <p>프로덕션 스케줄러는 매 사이클 {@code pollAndPublish()} 를 반복 호출하므로, 콜드 스타트에서
     * 첫 발행이 producer 타임아웃(D-013)을 초과해 PENDING 으로 남더라도 다음 사이클에 재발행되어 자가치유된다.
     * 단발 호출만 하던 테스트는 이 재시도 의미가 빠져 CI 콜드 스타트에서 간헐 실패했다(D-019).</p>
     */
    private void pollUntilPublished() {
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            outboxPollingService.pollAndPublish();
            assertThat(outboxEventRepository.findPendingEvents(100)).isEmpty();
        });
    }

    /**
     * headerCapture 큐에서 주어진 key 와 일치하는 record 를 찾을 때까지 polling.
     * 같은 클래스의 다른 테스트가 발행한 stale record 가 큐에 섞여 있을 수 있으므로
     * key (= payment aggregateId = orderId) 로 현재 테스트의 record 를 식별한다.
     */
    private ConsumerRecord<String, String> awaitRecordWithKey(String key) {
        long deadline = System.currentTimeMillis() + 10_000L;
        while (System.currentTimeMillis() < deadline) {
            for (ConsumerRecord<String, String> r : headerCapture.records) {
                if (key.equals(r.key())) {
                    return r;
                }
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting record", e);
            }
        }
        throw new AssertionError("no record with key=" + key + " arrived within timeout");
    }

    /** headerCapture 큐에서 주어진 key 와 일치하는 record 수를 센다 (중복 발행 검증용). */
    private long countRecordsWithKey(String key) {
        return headerCapture.records.stream().filter(r -> key.equals(r.key())).count();
    }

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    /** 고유 orderId 로 in-memory Payment 생성 (outbox 발행은 FK 무관 → 영속 불요). */
    private Payment newPayment() {
        long orderId = ORDER_ID_SEQ.getAndIncrement();
        return Payment.create(orderId, USER_ID, 100_000L);
    }
}
