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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * product-service 의 DLQ 원장 적재 통합 테스트 (계획 ④-c-2a §6 P6·P7).
 *
 * <p><b>실제 Kafka 왕복으로 배선을 고정한다.</b> recorder 직접 호출은 group/factory/토픽이 틀려도
 * 통과한다 — ④-c-1b 에서 확인된 false-green 유형이다.
 *
 * <p>멱등·malformed·종결 전이 등 서비스 무관 동작은 order-service 의 동일 테스트가 전수로 덮으므로
 * 여기서는 <b>이 서비스의 소유권 배선</b>만 본다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // ADR-0029: 리스너는 테스트에서 기본 off 다. 실제 Kafka 왕복으로 DLQ listener 배선을 고정하는 것이 검증 대상이다.
        "app.kafka.listener.enabled=true"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
// 켠 자율 writer 의 수명을 자기 클래스에 가둔다 (ADR-0029 D3) — context 가 캐시된 채 남으면
// 리스너가 이후 클래스의 공유 브로커·DB 를 계속 고친다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("product-service DLQ 원장 적재 통합 테스트")
class DeadLetterLedgerIntegrationTest extends AbstractIntegrationTest {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired DeadLetterRecordJpaRepository repository;

    private static final String DLQ_TOPIC = "payment.completed.dlq";
    private static final String ORIGIN_TOPIC = "payment.completed";
    private static final String OWNED_GROUP = "product-svc-payment-completed-group";
    private static final String FOREIGN_GROUP = "order-svc-payment-completed-group";

    @BeforeEach
    void setUp() {
        cleanDatabase();
        repository.deleteAll();
    }

    @Test
    @DisplayName("자기 group 실패분이 원장 1행으로 남는다")
    void ownedGroupIsRecorded() {
        send(DLQ_TOPIC, ORIGIN_TOPIC, 1, 42L, OWNED_GROUP);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findAll()).hasSize(1));

        DeadLetterRecord record = repository.findAll().get(0);
        assertThat(record.getOriginKind()).isEqualTo(DlqOriginKind.RESOLVED_ORIGIN);
        assertThat(record.getOriginTopic()).isEqualTo(ORIGIN_TOPIC);
        assertThat(record.getFailedConsumerGroup()).isEqualTo(OWNED_GROUP);
        assertThat(record.statusValue()).isEqualTo(DeadLetterStatus.OPEN);
    }

    @Test
    @DisplayName("남의 group 실패분은 적재하지 않는다 — 공유 DLQ 에서 중복 기록 0 (음성 대조)")
    void foreignGroupIsSkipped() {
        send(DLQ_TOPIC, ORIGIN_TOPIC, 1, 43L, FOREIGN_GROUP);
        // 대조군 — listener 가 살아있음을 증명한다
        send(DLQ_TOPIC, ORIGIN_TOPIC, 1, 44L, OWNED_GROUP);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findAll()).hasSize(1));

        assertThat(repository.findAll().get(0).getFailedConsumerGroup()).isEqualTo(OWNED_GROUP);
    }

    @Test
    @DisplayName("group 헤더 부재분을 자기 발행 토픽에서 적재한다 — 발행 서비스가 단일 quarantine 소유자")
    void quarantineRecordsGroupless() {
        sendWithoutGroup("product.updated.dlq", "product.updated", 0, 7L);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findAll()).hasSize(1));

        DeadLetterRecord record = repository.findAll().get(0);
        assertThat(record.getFailedConsumerGroup()).isEqualTo(DlqOrigin.UNKNOWN_CONSUMER_GROUP);
        assertThat(record.getOriginTopic()).isEqualTo("product.updated");
    }

    @Test
    @DisplayName("group 이 판독된 레코드는 quarantine listener 가 건드리지 않는다 — 이중 적재 0")
    void quarantineSkipsResolvedGroup() {
        send("product.updated.dlq", "product.updated", 0, 8L, "someone-else-group");
        sendWithoutGroup("product.updated.dlq", "product.updated", 0, 9L);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findAll()).hasSize(1));

        assertThat(repository.findAll().get(0).getOriginOffset()).isEqualTo(9L);
    }


    private void send(String dlqTopic, String originTopic, int partition, long offset, String group) {
        ProducerRecord<String, String> record = baseRecord(dlqTopic, originTopic, partition, offset);
        record.headers().add(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP, group.getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(record);
    }

    private void sendWithoutGroup(String dlqTopic, String originTopic, int partition, long offset) {
        kafkaTemplate.send(baseRecord(dlqTopic, originTopic, partition, offset));
    }

    private ProducerRecord<String, String> baseRecord(String dlqTopic, String originTopic,
                                                      int partition, long offset) {
        ProducerRecord<String, String> record = new ProducerRecord<>(dlqTopic, "key-1", "{\"eventId\":\"evt-1\"}");
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
