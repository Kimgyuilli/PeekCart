package com.peekcart.global.replay;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

import java.time.Duration;
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
 * <h2>{@code Admin} 생성 방식</h2>
 * {@code KafkaAdmin#createAdmin()} 은 <b>protected</b> 라 호출할 수 없다(spring-kafka 3.3.x).
 * 공개 API 인 {@link KafkaAdmin#getConfigurationProperties()} 로 설정을 얻어 {@link Admin#create} 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OriginalRecordReader {

    private static final Duration ADMIN_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(10);

    private final KafkaAdmin kafkaAdmin;
    private final ConsumerFactory<String, String> consumerFactory;

    /**
     * @param record    읽어온 원본. {@code rejection} 이 있으면 {@code null}
     * @param rejection 거부 사유(ADR §D5-2 축 4 — 좌표 무효). 통과면 {@code null}
     */
    public record Result(ConsumerRecord<String, String> record, String rejection) {

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

        String configRejection = checkCleanupPolicy(topic);
        if (configRejection != null) {
            return new Result(null, configRejection);
        }

        try (Consumer<String, String> consumer = consumerFactory.createConsumer(null, "-pc-replay-reader")) {
            consumer.assign(List.of(tp));

            long beginning = consumer.beginningOffsets(List.of(tp)).getOrDefault(tp, 0L);
            long end = consumer.endOffsets(List.of(tp)).getOrDefault(tp, 0L);
            if (offset < beginning || offset >= end) {
                return new Result(null, String.format(
                        "좌표가 보존 범위 밖이다 — %s-%d offset=%d, 브로커 보유 구간=[%d, %d)",
                        topic, partition, offset, beginning, end));
            }

            consumer.seek(tp, offset);
            ConsumerRecords<String, String> polled = consumer.poll(POLL_TIMEOUT);
            for (ConsumerRecord<String, String> record : polled.records(tp)) {
                if (record.offset() == offset) {
                    return new Result(record, null);
                }
                // 요청 offset 에 레코드가 없다 — compaction 으로 사라졌거나 이미 삭제됐다.
                return new Result(null, String.format(
                        "요청 offset 에 레코드가 없다 — 요청=%d, 반환=%d (compaction hole)",
                        offset, record.offset()));
            }
            return new Result(null, String.format(
                    "원본 레코드를 읽지 못했다 — %s-%d offset=%d (poll 타임아웃 %s)",
                    topic, partition, offset, POLL_TIMEOUT));
        }
    }

    /**
     * 업무 토픽이 <b>compact</b> 정책이면 읽기를 신뢰할 수 없다 — 같은 key 의 옛 레코드는
     * 조용히 사라지고 offset 은 그대로 남는다.
     */
    private String checkCleanupPolicy(String topic) {
        ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
        try (Admin admin = Admin.create(kafkaAdmin.getConfigurationProperties())) {
            Map<ConfigResource, Config> described = admin
                    .describeConfigs(List.of(resource))
                    .all()
                    .get(ADMIN_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            ConfigEntry cleanupPolicy = described.get(resource).get("cleanup.policy");
            if (cleanupPolicy != null && cleanupPolicy.value() != null
                    && cleanupPolicy.value().contains("compact")) {
                return "토픽이 compact 정책이라 좌표 읽기를 신뢰할 수 없다 — " + topic
                        + " cleanup.policy=" + cleanupPolicy.value();
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
