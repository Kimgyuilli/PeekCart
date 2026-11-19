package com.peekcart.global.outbox;

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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 재발행 보장 수준 = <b>publication at-least-once</b> 실측 (ADR-0020 §D1).
 *
 * <p><b>이 테스트는 "중복 발행 0" 을 단언하지 않는다.</b> DB 커밋과 broker ack 를 원자적으로 묶는 수단이
 * 우리 스택에 없다는 것이 ADR-0020 D1 의 결정이고, 여기서는 그 결정이 <b>참임을 관측</b>한다 —
 * 계약을 "중복은 일어날 수 있다 + 소비 효과는 1회" 로 적어두고 검증에서 반대를 주장하면 둘 중 하나가 거짓이다.
 *
 * <h2>무엇을 실패시키는가</h2>
 * {@link OutboxPollingService} 는 사이클에 ack 받은 이벤트를 루프가 끝난 뒤 {@code markPublished} 한 문장으로
 * 기록한다(ADR-0049 D1). 그 문장 하나를 실패시키면 프로세스가 ack 와 기록 사이에서 죽은 것과 DB 결과가 같다.
 * <b>중복 범위는 이벤트 1건이 아니라 그 사이클의 ack 전부</b>다(ADR-0049 D3). 그래서 이벤트를 여러 건 넣고
 * 전부가 다시 발행되는지 본다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 배경 잡은 ADR-0029 로 전역 off 다. 이 테스트는 "같은 이벤트가 몇 번 발행됐나" 를 세므로
        // 같은 행을 집어가는 다른 발행 주체가 있으면 관측이 흔들린다. 사이클은 테스트가 직접 돌린다.
        // 첫 발행이 메타데이터 조회 등으로 6s 기본 타임아웃을 넘기면 그 사이클의 레코드가 조용히 사라져
        // "중복이 안 났다" 로 오독된다(실측: 살아남은 레코드의 offset 이 0이었다).
        "app.outbox.polling.publish-timeout=30s"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("outbox 재발행 보장 = at-least-once")
class OutboxAtLeastOnceIntegrationTest extends AbstractIntegrationTest {

    @MockitoSpyBean OutboxEventRepository outboxEventRepository;
    @Autowired OutboxPollingService pollingService;
    @Autowired OutboxEventJpaRepository outboxEventJpaRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    private static final String TOPIC = "order.created";

    /**
     * 묶음 기록 실패 주입 스위치.
     *
     * <p><b>Mockito 재스터빙 대신 플래그를 쓰는 이유</b>: 이 spy 는 테스트 스레드만 쓰는 것이 아니다.
     * {@code OutboxPollingScheduler} 가 {@code @Scheduled} 주기로 <b>같은 빈을 동시에</b>
     * 호출한다. 그 상태에서 {@code doThrow(...)} → {@code doCallRealMethod(...)} 로 다시 스터빙하면
     * Mockito 의 스터빙 구간과 배경 스레드의 호출이 겹쳐 <b>새 answer 가 반영되지 않는다</b> —
     * CI 에서 두 번째 poll 이 여전히 "DB down" 을 던져 실패했다(로컬은 타이밍으로 통과했다).
     *
     * <p>answer 를 <b>한 번만</b> 심고 플래그로 분기하면 재스터빙 구간 자체가 없어진다.
     */
    private final AtomicBoolean failMarkPublished = new AtomicBoolean(false);

    private static final int EVENTS = 3;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        // 공유 브로커라 앞 클래스가 남긴 레코드가 seekToBeginning 에 읽힌다 (D-032).
        cleanKafkaTopics(SharedContainers.KAFKA.getBootstrapServers(), "order.created");
        outboxEventJpaRepository.deleteAll();
        // **토픽이 준비된 뒤에 측정한다.** 컨테이너가 여럿 뜬 느린 실행에서는 토픽 생성이 끝나기 전에
        // 첫 send 가 나가 메타데이터 대기로 타임아웃한다 — 그러면 그 사이클의 레코드가 아예 생기지 않고
        // (실측: broker end offset 이 전부 0), 테스트는 "중복이 안 났다" 로 잘못 읽는다.
        // 이 테스트가 재는 것은 재발행이지 토픽 프로비저닝이 아니므로 전제를 명시적으로 갖춘다.
        awaitTopicReady();

        failMarkPublished.set(false);
        doAnswer(invocation -> {
            if (failMarkPublished.get()) {
                throw new RuntimeException("DB down");
            }
            return invocation.callRealMethod();
        }).when(outboxEventRepository).markPublished(any(), any());
    }

    @Test
    @DisplayName("ack 후 묶음 기록이 실패하면 그 사이클의 이벤트 전부가 다시 발행되고, 각 레코드는 eventId 를 보존한다")
    void publicationIsAtLeastOnceAndConsumptionIsIdempotent() {
        List<String> eventIds = new ArrayList<>();
        for (int i = 0; i < EVENTS; i++) {
            String eventId = UUID.randomUUID().toString();
            insertPending(eventId);
            eventIds.add(eventId);
        }

        // 사이클 끝의 묶음 기록을 죽인다 — 프로세스가 ack 와 기록 사이에서 죽은 상황과 DB 에 남는 결과가 같다.
        failMarkPublished.set(true);
        // 기록 실패는 삼키지 않고 사이클 밖으로 나간다(ADR-0049 D3). 삼키면 PENDING 인 채 실패가 숨는다.
        assertThatThrownBy(() -> pollingService.pollAndPublish())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");

        // **1사이클이 실제로 broker 에 닿았음을 여기서 확인한다.** 이 단언이 없으면 send 자체가 실패한
        // 경우와 "send 성공 후 기록 실패" 가 구분되지 않는다.
        // **소비가 아니라 broker 의 end offset 으로 센다.** consumer group 조인·리밸런스·fetch 타이밍이
        // 끼어들면 "이미 ack 된 레코드를 못 봤다" 와 "생산이 안 됐다" 가 구분되지 않는다.
        assertThat(brokerRecordCount()).as("1사이클 발행 전부가 broker 에 ack 됐다").isEqualTo(EVENTS);

        // ack 받은 이벤트 **전부**가 PENDING 으로 남는다. 중복 범위가 사이클 단위라는 것이 이 단언이다.
        for (String eventId : eventIds) {
            assertThat(statusInDb(eventId)).as(eventId).isEqualTo("PENDING");
        }

        // 장애 복구 후 다음 사이클.
        failMarkPublished.set(false);
        pollingService.pollAndPublish();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            for (String eventId : eventIds) {
                assertThat(statusInDb(eventId)).as(eventId).isEqualTo("PUBLISHED");
            }
        });

        // broker 에는 각 이벤트가 **2개 이상** 있다. 이것이 at-least-once 다.
        // **정확한 수를 단언하지 않는다.** ADR-0020 D1 은 중복 수에 상한을 두지 않는다 — 계약이 말하지 않는
        // 수를 단언하면 그 수가 바뀔 때 계약이 아니라 테스트가 깨진다.
        assertThat(brokerRecordCount()).as("2사이클이 같은 이벤트들을 다시 발행했다")
                .isGreaterThanOrEqualTo(2L * EVENTS);

        List<ConsumerRecord<String, String>> records = drain(TOPIC, 2 * EVENTS);
        assertThat(records).extracting(ConsumerRecord::offset).doesNotHaveDuplicates();
        // 소비자의 processed_events (event_id, consumer_group) UNIQUE 가 효과를 1회로 접는다. 그 멱등이 성립하려면
        // eventId 가 재발행에도 보존돼야 한다 — 이벤트마다 같은 payload 가 2개 이상 있어야 한다.
        for (String eventId : eventIds) {
            assertThat(records).filteredOn(r -> r.value().contains(eventId))
                    .as(eventId)
                    .hasSizeGreaterThanOrEqualTo(2)
                    .extracting(ConsumerRecord::value)
                    .containsOnly("{\"eventId\":\"" + eventId + "\"}");
        }
    }

    private void insertPending(String eventId) {
        jdbcTemplate.update("""
                INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, event_id, payload,
                                           status, retry_count, created_at, record_kind)
                VALUES ('ORDER', 'order-1', ?, ?, ?, 'PENDING', 0, NOW(6), 'DOMAIN')
                """, TOPIC, eventId, "{\"eventId\":\"" + eventId + "\"}");
    }

    private String statusInDb(String eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM outbox_events WHERE event_id = ?", String.class, eventId);
    }

    private void awaitTopicReady() {
        await().atMost(Duration.ofSeconds(60)).until(() -> {
            Properties props = new Properties();
            props.put("bootstrap.servers", SharedContainers.KAFKA.getBootstrapServers());
            props.put("group.id", "test-topic-ready-" + UUID.randomUUID());
            props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
                var partitions = consumer.partitionsFor(TOPIC);
                return partitions != null && !partitions.isEmpty()
                        && partitions.stream().allMatch(info -> info.leader() != null);
            } catch (Exception e) {
                return false;
            }
        });
    }

    /** broker 가 실제로 갖고 있는 레코드 총수. 소비 없이 재므로 group 조율·fetch 타이밍이 개입하지 않는다. */
    private long brokerRecordCount() {
        Properties props = new Properties();
        props.put("bootstrap.servers", SharedContainers.KAFKA.getBootstrapServers());
        props.put("group.id", "test-endoffsets-" + UUID.randomUUID());
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(TOPIC).stream()
                    .map(info -> new TopicPartition(TOPIC, info.partition()))
                    .toList();
            // **beginning 을 빼고 센다.** 컨테이너를 공유하면서 cleanKafkaTopics 가
            // deleteRecords 로 로그를 자르는데, 그것은 low watermark 만 올리고 end offset 은
            // 그대로 둔다. 절대값으로 세면 앞 클래스가 발행한 수가 그대로 더해진다(D-032).
            var end = consumer.endOffsets(partitions);
            var begin = consumer.beginningOffsets(partitions);
            return partitions.stream()
                    .mapToLong(tp -> end.get(tp) - begin.get(tp))
                    .sum();
        }
    }

    private List<ConsumerRecord<String, String>> drain(String topic, int expected) {
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
            while (collected.size() < expected && System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(500));
                polled.records(topic).forEach(collected::add);
            }
            return collected;
        }
    }
}
