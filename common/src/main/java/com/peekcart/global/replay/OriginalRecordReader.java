package com.peekcart.global.replay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import com.peekcart.global.retention.IdempotencyRetentionProperties;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 기록된 좌표에서 <b>원본 레코드</b>를 읽는다 (ADR-0020 §D5-1 · 구현 ④-c-2b-4a P18).
 *
 * <p>replay 의 원본은 원장의 진단 사본({@code payload} 컬럼, 잘려 있다)이 아니라 <b>브로커에 남아 있는
 * 그 레코드</b>다. 사본으로 재발행하면 절단·변형분이 원본 토픽에 주입된다.
 *
 * <h2>compaction hole 방어</h2>
 * 요청 offset 에 레코드가 없으면 {@code poll} 은 <b>그 다음 레코드</b>를 돌려준다. 그대로 쓰면
 * <b>엉뚱한 메시지가 발행</b>되므로, 반환 레코드의 offset 이 요청 offset 과 <b>정확히 같은지</b> 확인한다.
 *
 * <h2>consumer group 을 만들지 않는다</h2>
 * {@code subscribe} 가 아니라 {@code assign} 만 쓰고 offset 을 커밋하지 않는다 — 진단 목적의 읽기가
 * 업무 group 의 offset 을 움직이면 그 group 이 메시지를 건너뛴다.
 *
 * <h2>원본 <b>바이트</b>로 읽는다 (diff 리뷰 1R #1)</h2>
 * 공용 {@code ConsumerFactory<String, String>} 로 읽으면 {@code StringDeserializer} 가 유효하지 않은
 * UTF-8 을 <b>대체 문자로 손실 변환</b>한다. 그러면 fence 의 "key·payload byte-for-byte 동일"(§D8-3)과
 * digest 가 <b>원본이 아니라 변환된 문자열</b> 기준이 되어, 변조된 재발행을 승인할 수 있다.
 * 그래서 {@code byte[]} 로 읽고 <b>strict decode → 재인코딩 일치</b>를 확인한 뒤에만 문자열로 넘긴다.
 * 왕복이 깨지면 <b>거부</b>한다(outbox {@code payload} 가 TEXT 라 바이트를 그대로 실을 수 없으므로,
 * 실을 수 없는 레코드는 replay 대상이 아니다).
 *
 * <h2>빈으로 자동 등록하지 않는다</h2>
 * {@code @Component} 로 두면 <b>Kafka 가 없는 서비스</b>(user-service)의 컨텍스트가 깨진다 —
 * 공통 스캔 대상이라 그 서비스도 이 빈을 만들려 하고 {@code KafkaAdmin} 이 없어 실패한다.
 * 그래서 <b>원장을 가진 4서비스의 Kafka 설정이 각자 {@code @Bean} 으로 등록</b>한다
 * (ADR-0011 §D2 — 서비스는 자기 소비 경로를 소유한다).
 *
 * <h2>{@code Admin} 생성 방식</h2>
 * {@code KafkaAdmin#createAdmin()} 은 <b>protected</b> 라 호출할 수 없다(spring-kafka 3.3.x).
 * 공개 API 인 {@link KafkaAdmin#getConfigurationProperties()} 로 설정을 얻어 {@link Admin#create} 한다.
 */
@Slf4j
@RequiredArgsConstructor
public class OriginalRecordReader {

    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaAdmin kafkaAdmin;
    private final ConsumerFactory<String, String> consumerFactory;
    private final IdempotencyRetentionProperties retentionProperties;

    /** UTF-8 왕복이 검증된 원본 레코드. */
    public record Original(String key, String value, long timestamp, int partition, long offset) {
    }

    /**
     * @param record    읽어온 원본. {@code rejection} 이 있으면 {@code null}
     * @param rejection 거부 사유(ADR §D5-2 축 4 — 좌표 무효). 통과면 {@code null}
     */
    public record Result(Original record, String rejection) {

        public boolean rejected() {
            return rejection != null;
        }
    }

    /**
     * 좌표를 검증하고 원본 레코드를 읽는다.
     *
     * <p>순서가 계약이다 — <b>먼저 경계를 확인하고</b> 그 다음에 읽는다. 읽고 나서 판정하면
     * 범위 밖 좌표에서 {@code poll} 이 무한정 기다리거나 엉뚱한 레코드를 돌려준다.
     */
    public Result read(String topic, int partition, long offset) {
        TopicPartition tp = new TopicPartition(topic, partition);

        String configRejection = checkTopicConfig(topic);
        if (configRejection != null) {
            return new Result(null, configRejection);
        }

        try (Consumer<byte[], byte[]> consumer = byteConsumer()) {
            consumer.assign(List.of(tp));

            long beginning = consumer.beginningOffsets(List.of(tp)).getOrDefault(tp, 0L);
            long end = consumer.endOffsets(List.of(tp)).getOrDefault(tp, 0L);
            if (offset < beginning || offset >= end) {
                return new Result(null, String.format(
                        "좌표가 보존 범위 밖이다 — %s-%d offset=%d, 브로커 보유 구간=[%d, %d)",
                        topic, partition, offset, beginning, end));
            }

            consumer.seek(tp, offset);
            ConsumerRecords<byte[], byte[]> polled = consumer.poll(POLL_TIMEOUT);
            for (ConsumerRecord<byte[], byte[]> record : polled.records(tp)) {
                if (record.offset() == offset) {
                    return decode(record);
                }
                // 요청 offset 에 레코드가 없다 — compaction 으로 사라졌거나 이미 삭제됐다.
                return new Result(null, String.format(
                        "요청 offset 에 레코드가 없다 — 요청=%d, 반환=%d (compaction hole)",
                        offset, record.offset()));
            }
            return new Result(null, String.format(
                    "원본 레코드를 읽지 못했다 — %s-%d offset=%d (poll 타임아웃 %s)",
                    topic, partition, offset, POLL_TIMEOUT));
        } catch (org.apache.kafka.common.errors.InterruptException e) {
            // Kafka 의 InterruptException 은 RuntimeException 이라 아래 catch 에 먹힌다. 먼저 잡아
            // **interrupt 상태를 보존**한 뒤 거부 사유로 바꾼다.
            Thread.currentThread().interrupt();
            return new Result(null, "원본 읽기가 중단됐다 — " + topic + "-" + partition);
        } catch (Exception e) {
            // **좌표 문제를 500 으로 전파하지 않는다** (diff 리뷰 2R #4). 존재하지 않는 파티션·권한 차이·
            // consumer 설정 오류는 전부 "이 좌표는 읽을 수 없다" 이며, P18 의 Result 가 표현하는 계약이다.
            log.error("[DLQ-REPLAY] 원본 읽기 실패 — {}-{} offset={}", topic, partition, offset, e);
            return new Result(null, String.format("원본을 읽지 못했다 — %s-%d offset=%d: %s",
                    topic, partition, offset, e.getMessage()));
        }
    }

    /**
     * {@code byte[]} 전용 consumer. 공용 factory 의 설정은 그대로 쓰고 <b>역직렬화기만</b> 바꾼다 —
     * bootstrap·보안 설정이 갈라지면 읽기가 다른 클러스터를 볼 수 있다.
     */
    private Consumer<byte[], byte[]> byteConsumer() {
        Map<String, Object> configs = new HashMap<>(consumerFactory.getConfigurationProperties());
        configs.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        configs.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        // group 을 만들지 않으므로 group.id 는 무의미하지만, 없으면 일부 설정 조합에서 생성이 실패한다.
        configs.put(ConsumerConfig.GROUP_ID_CONFIG, "pc-replay-reader");
        configs.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        return new DefaultKafkaConsumerFactory<byte[], byte[]>(configs).createConsumer();
    }

    /**
     * 원본 바이트를 <b>strict</b> UTF-8 로 디코드하고 <b>재인코딩이 원본과 같은지</b> 확인한다.
     * 어긋나면 거부한다 — 손실 변환분을 재발행하면 fence 가 주장하는 byte 동일성이 거짓이 된다.
     */
    private Result decode(ConsumerRecord<byte[], byte[]> record) {
        String key;
        String value;
        try {
            key = strictUtf8(record.key());
            value = strictUtf8(record.value());
        } catch (CharacterCodingException e) {
            return new Result(null, "원본 레코드가 유효한 UTF-8 이 아니다 — 손실 없이 재발행할 수 없다: "
                    + e.getMessage());
        }
        if (value == null) {
            // tombstone(payload null)은 outbox payload NOT NULL 로 표현할 수 없다(계획 §10 R3).
            return new Result(null, "원본 payload 가 null(tombstone)이라 재발행할 수 없다");
        }
        return new Result(new Original(key, value, record.timestamp(), record.partition(), record.offset()), null);
    }

    private String strictUtf8(byte[] bytes) throws CharacterCodingException {
        if (bytes == null) {
            return null;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String decoded = decoder.decode(ByteBuffer.wrap(bytes)).toString();
        if (!Arrays.equals(decoded.getBytes(StandardCharsets.UTF_8), bytes)) {
            throw new CharacterCodingException();
        }
        return decoded;
    }

    /**
     * 토픽 설정을 <b>실측</b>해 좌표 읽기의 전제를 확인한다 (ADR-0020 §D5-1 · diff 리뷰 1R #7).
     *
     * <ul>
     *   <li><b>compact</b> 정책이면 같은 key 의 옛 레코드가 조용히 사라지고 offset 만 남는다</li>
     *   <li><b>{@code retention.ms} 가 replay 창보다 짧으면</b> 안전창 안의 사건인데도 원본이 이미
     *       삭제될 수 있다. 부팅 시 검사({@code isRetentionCoveringReplaySafetyMargin})는 <b>선언값</b>을
     *       보므로 운영 중 브로커 설정 drift 를 잡지 못한다 — 여기서 실측한다</li>
     * </ul>
     */
    private String checkTopicConfig(String topic) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            Map<ConfigResource, Config> described = admin
                    .describeConfigs(List.of(resource))
                    .all()
                    .get(ADMIN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            Config config = described.get(resource);
            ConfigEntry cleanupPolicy = config.get("cleanup.policy");
            if (cleanupPolicy != null && cleanupPolicy.value() != null
                    && cleanupPolicy.value().contains("compact")) {
                return "토픽이 compact 정책이라 좌표 읽기를 신뢰할 수 없다 — " + topic
                        + " cleanup.policy=" + cleanupPolicy.value();
            }

            ConfigEntry retention = config.get("retention.ms");
            Duration required = retentionProperties.getFloor().getDlqReplayWindow();
            if (retention == null || retention.value() == null) {
                return "토픽 retention.ms 를 읽지 못했다 — " + topic;
            }
            long retentionMs;
            try {
                retentionMs = Long.parseLong(retention.value().trim());
            } catch (NumberFormatException e) {
                return "토픽 retention.ms 를 해석하지 못했다 — " + topic + " retention.ms=" + retention.value();
            }
            // -1 = 무한 보존. 그 경우만 창 비교를 건너뛴다.
            if (retentionMs != -1L && retentionMs < required.toMillis()) {
                return "토픽 retention.ms 가 replay 창보다 짧다 — " + topic
                        + " retention.ms=" + retentionMs + ", 필요=" + required.toMillis();
            }
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "토픽 설정 조회가 중단됐다 — " + topic;
        } catch (Exception e) {
            // fail-closed: 설정을 모르면 읽기를 허용하지 않는다.
            log.error("[DLQ-REPLAY] 토픽 설정 조회 실패 — topic={}", topic, e);
            return "토픽 설정을 조회하지 못했다 — " + topic + ": " + e.getMessage();
        }
    }
}
