package com.peekcart.global.replay;

import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 좌표 reader 계약 (구현 ④-c-2b-4a P18 · ADR-0020 §D5-1 · diff 리뷰 1R #1·#7, 2R #4·#5).
 *
 * <p><b>왜 별도 통합테스트인가</b>: 진입점 테스트는 정상 UTF-8·정상 retention·정상 payload 만 지나간다.
 * 그래서 {@code byte[]} consumer 로의 전환, strict UTF-8 왕복, tombstone 거부, retention 실측,
 * compact 거부, 좌표 범위 밖 처리가 <b>깨져도 green</b> 이었다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "app.outbox.polling.delay=1h",
        "app.dead-letter.reconcile.delay=1h"
})
@Import(IntegrationTestConfig.class)
@DisplayName("replay 좌표 reader")
class OriginalRecordReaderIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired OriginalRecordReader reader;

    /** 7일 — {@code app.idempotency.floor.dlq-replay-window} 와 같은 값을 리터럴로 독립 기재(N17). */
    private static final long REPLAY_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;

    @Test
    @DisplayName("기록된 좌표의 원본을 그대로 읽는다")
    void readsOriginal() {
        String topic = newTopic(Map.of("retention.ms", String.valueOf(REPLAY_WINDOW_MS)));
        long offset = send(topic, "k1".getBytes(StandardCharsets.UTF_8), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));

        OriginalRecordReader.Result result = reader.read(topic, 0, offset);

        assertThat(result.rejected()).isFalse();
        assertThat(result.record().key()).isEqualTo("k1");
        assertThat(result.record().value()).isEqualTo("{\"a\":1}");
        assertThat(result.record().offset()).isEqualTo(offset);
    }

    @Test
    @DisplayName("retention.ms = -1(무한)은 통과한다")
    void infiniteRetentionIsAllowed() {
        String topic = newTopic(Map.of("retention.ms", "-1"));
        long offset = send(topic, "k".getBytes(StandardCharsets.UTF_8), "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(reader.read(topic, 0, offset).rejected()).isFalse();
    }

    @Test
    @DisplayName("유효하지 않은 UTF-8 은 거부한다 — 손실 변환분을 재발행하면 byte 동일성 주장이 거짓이 된다")
    void invalidUtf8IsRejected() {
        String topic = newTopic(Map.of("retention.ms", String.valueOf(REPLAY_WINDOW_MS)));
        // 0xFF 는 UTF-8 로 표현되지 않는 바이트다. StringDeserializer 였다면 U+FFFD 로 조용히 바뀐다.
        long offset = send(topic, "k".getBytes(StandardCharsets.UTF_8), new byte[]{(byte) 0xFF, (byte) 0xFE});

        OriginalRecordReader.Result result = reader.read(topic, 0, offset);

        assertThat(result.rejected()).isTrue();
        assertThat(result.rejection()).contains("UTF-8");
    }

    @Test
    @DisplayName("tombstone(payload null)은 거부한다 — outbox payload 가 NOT NULL 이라 실을 수 없다")
    void tombstoneIsRejected() {
        String topic = newTopic(Map.of("retention.ms", String.valueOf(REPLAY_WINDOW_MS)));
        long offset = send(topic, "k".getBytes(StandardCharsets.UTF_8), null);

        assertThat(reader.read(topic, 0, offset).rejection()).contains("tombstone");
    }

    @Test
    @DisplayName("retention.ms 가 replay 창보다 짧으면 거부한다 — 부팅 검사는 선언값이라 drift 를 못 잡는다")
    void shortRetentionIsRejected() {
        String topic = newTopic(Map.of("retention.ms", String.valueOf(REPLAY_WINDOW_MS - 1)));
        long offset = send(topic, "k".getBytes(StandardCharsets.UTF_8), "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(reader.read(topic, 0, offset).rejection()).contains("retention.ms");
    }

    @Test
    @DisplayName("compact 토픽은 거부한다 — 같은 key 의 옛 레코드가 사라져도 offset 은 남는다")
    void compactTopicIsRejected() {
        String topic = newTopic(Map.of("cleanup.policy", "compact"));
        long offset = send(topic, "k".getBytes(StandardCharsets.UTF_8), "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(reader.read(topic, 0, offset).rejection()).contains("compact");
    }

    @Test
    @DisplayName("보존 범위 밖 offset 은 거부한다")
    void offsetOutOfRangeIsRejected() {
        String topic = newTopic(Map.of("retention.ms", String.valueOf(REPLAY_WINDOW_MS)));
        send(topic, "k".getBytes(StandardCharsets.UTF_8), "{}".getBytes(StandardCharsets.UTF_8));

        assertThat(reader.read(topic, 0, 999L).rejection()).contains("보존 범위 밖");
    }

    @Test
    @DisplayName("존재하지 않는 토픽·파티션은 500 이 아니라 거부 사유가 된다")
    void unknownCoordinatesBecomeRejection() {
        String topic = newTopic(Map.of("retention.ms", String.valueOf(REPLAY_WINDOW_MS)));

        // 파티션 1 은 없다(파티션 1개짜리 토픽). 예외가 전파되면 운영자는 500 만 본다.
        assertThat(reader.read(topic, 1, 0L).rejected()).isTrue();
        assertThat(reader.read("존재하지-않는-토픽", 0, 0L).rejected()).isTrue();
    }

    private String newTopic(Map<String, String> configs) {
        String name = "reader-test-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(name, 1, (short) 1).configs(configs))).all().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 토픽 생성 실패", e);
        }
        return name;
    }

    private long send(String topic, byte[] key, byte[] value) {
        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("key.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(props)) {
            return producer.send(new ProducerRecord<>(topic, 0, key, value)).get().offset();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (Exception e) {
            throw new IllegalStateException("테스트 레코드 발행 실패", e);
        }
    }
}
