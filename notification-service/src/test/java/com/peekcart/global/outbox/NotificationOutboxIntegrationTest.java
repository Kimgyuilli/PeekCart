package com.peekcart.global.outbox;

import com.peekcart.global.retention.OutboxRetentionProperties;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * notification-service 의 outbox 신설 회귀 (구현 ④-c-2b-2 P9 · 계획 §6 V-32 · ADR-0020 §D2·§D8).
 *
 * <p><b>왜 소비 전용 서비스에 outbox 가 있는가</b>: DLQ replay 는 원장 소유 서비스가 자기 원장 행을
 * 재발행하는 것이고(D8-3 fence), notification 도 자기 원장을 갖는다. 재발행은 다른 발행과 같은 outbox
 * 경로를 탄다(D3 — 별도 replay_outbox 를 만들지 않는다).
 *
 * <p><b>도메인 행으로 관측하는 이유</b>: 이 PR 에는 replay 개시 진입점이 없어(④-c-2b-4) replay 행을
 * 만들 수 없다. 그렇다고 "빈이 존재한다" 로 끝내면 <b>배선됐다 수준의 판정</b>이 된다 — poller 를
 * 통째로 지워도 통과한다. 그래서 outbox 행을 직접 넣고 <b>broker 에 도착하는지</b>를 본다.
 * 발행 경로 자체는 서비스 무관이므로 그것으로 충분하다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
        // 발행 주체는 테스트다 — 배경 poller 가 먼저 집어가면 "poller 를 지워도 통과" 하는 배선 검사로
        // 퇴화한다. 스케줄러는 테스트 기본 off 라(ADR-0029) 타이머를 따로 늦추지 않는다.
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("notification-service outbox 신설")
class NotificationOutboxIntegrationTest extends AbstractIntegrationTest {

    @Autowired OutboxPollingService pollingService;
    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired OutboxRetentionProperties retentionProperties;
    @Autowired JdbcTemplate jdbcTemplate;

    @Value("${app.outbox.lock-name}")
    String lockName;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        outboxEventJpaRepository.deleteAll();
        awaitTopicsReady("notification.probe");
    }

    @Test
    @DisplayName("outbox 행이 실제로 broker 에 발행되고 PUBLISHED 로 봉인된다")
    void pollerActuallyPublishes() {
        OutboxEvent event = OutboxEvent.create("NOTIFICATION", "notif-1", "notification.probe",
                null, null, id -> "{\"eventId\":\"" + id + "\"}");
        OutboxEvent saved = outboxEventRepository.save(event);

        pollingService.pollAndPublish();

        List<ConsumerRecord<String, String>> records = drain("notification.probe");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).key()).isEqualTo("notif-1");
        assertThat(outboxEventJpaRepository.findById(saved.getId()).orElseThrow().getStatus())
                .isEqualTo(OutboxEventStatus.PUBLISHED);
    }

    @Test
    @DisplayName("도메인 팩토리는 record_kind 를 DOMAIN 으로 명시한다 — DB DEFAULT 에 기대지 않는다")
    void domainFactoryStampsKindExplicitly() {
        OutboxEvent saved = outboxEventRepository.save(
                OutboxEvent.create("NOTIFICATION", "notif-2", "notification.probe",
                        null, null, id -> "{}"));

        String kind = jdbcTemplate.queryForObject(
                "SELECT record_kind FROM outbox_events WHERE id = ?", String.class, saved.getId());
        assertThat(kind).isEqualTo("DOMAIN");
    }

    @Test
    @DisplayName("record_kind 컬럼에 DB DEFAULT 가 없다 — 판별자 누락이 조용히 DOMAIN 이 되면 안 된다")
    void recordKindHasNoDatabaseDefault() {
        String columnDefault = jdbcTemplate.queryForObject("""
                SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'outbox_events'
                   AND COLUMN_NAME = 'record_kind'
                """, String.class);
        assertThat(columnDefault).isNull();
    }

    @Test
    @DisplayName("app.outbox.* 는 base yml 이 소유한다 — 프로파일 override 없이 값이 바인딩된다 (ADR-0007)")
    void outboxSettingsAreOwnedByBaseYaml() {
        // 이 값들이 프로파일로 새면 환경마다 발행 정책이 갈라진다. 동작 정책은 base 소유가 계약이다.
        assertThat(retentionProperties.getRetention()).isEqualTo(Duration.ofDays(7));
        // 락 이름이 다른 서비스와 겹치면 한쪽 poller 가 통째로 굶는다.
        assertThat(lockName).isEqualTo("notificationOutboxPollingJob");
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

    private List<ConsumerRecord<String, String>> drain(String topic) {
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
            List<ConsumerRecord<String, String>> collected = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 20_000;
            while (collected.isEmpty() && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.records(topic).forEach(collected::add);
            }
            return collected;
        }
    }
}
