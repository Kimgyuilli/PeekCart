package com.peekcart.global.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토픽 config 계약 (ADR-0020 §D4-1).
 *
 * <p>여기서 고정하는 것은 <b>값 자체가 아니라 계약</b>이다 — 어떤 키가 반드시 선언되는가,
 * 업무와 {@code .dlq} 가 무엇이 같고 무엇이 다른가, 그리고 시간값이 <b>주입된 출처에서 유도되는가</b>.
 * 값을 리터럴로 두 곳에 적으면 갈라지므로 유도 여부를 단언한다.
 */
@DisplayName("KafkaTopicConfigs — 토픽 config 계약 (ADR-0020 D4-1)")
class KafkaTopicConfigsTest {

    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Duration BEFORE_MAX = Duration.ofDays(9);

    @Test
    @DisplayName("계약 키 7종이 빠짐없이 선언된다")
    void declaresAllSevenContractKeys() {
        assertThat(KafkaTopicConfigs.business(RETENTION, BEFORE_MAX)).containsOnlyKeys(
                "retention.ms", "cleanup.policy", "retention.bytes",
                "segment.bytes", "segment.ms",
                "message.timestamp.type", "message.timestamp.before.max.ms");
        assertThat(KafkaTopicConfigs.dlq(RETENTION, BEFORE_MAX)).containsOnlyKeys(
                "retention.ms", "cleanup.policy", "retention.bytes",
                "segment.bytes", "segment.ms",
                "message.timestamp.type", "message.timestamp.before.max.ms");
    }

    @Test
    @DisplayName("시간값은 주입된 출처에서 유도된다 — 리터럴이 아니다")
    void timeValuesDeriveFromArguments() {
        Map<String, String> configs = KafkaTopicConfigs.business(Duration.ofDays(3), Duration.ofDays(11));
        assertThat(configs).containsEntry("retention.ms", String.valueOf(Duration.ofDays(3).toMillis()));
        assertThat(configs).containsEntry("message.timestamp.before.max.ms",
                String.valueOf(Duration.ofDays(11).toMillis()));
    }

    @Test
    @DisplayName("cleanup.policy 는 delete 다 — compact 는 좌표 hole 을 만들어 replay 가 다른 레코드를 읽게 한다")
    void cleanupPolicyIsDeleteNotCompact() {
        assertThat(KafkaTopicConfigs.business(RETENTION, BEFORE_MAX)).containsEntry("cleanup.policy", "delete");
        assertThat(KafkaTopicConfigs.dlq(RETENTION, BEFORE_MAX)).containsEntry("cleanup.policy", "delete");
    }

    @Test
    @DisplayName("message.timestamp.type 은 CreateTime 이다 — LogAppendTime 이면 broker 가 원본 timestamp 를 덮어쓴다")
    void timestampTypeIsCreateTime() {
        assertThat(KafkaTopicConfigs.business(RETENTION, BEFORE_MAX))
                .containsEntry("message.timestamp.type", "CreateTime");
        assertThat(KafkaTopicConfigs.dlq(RETENTION, BEFORE_MAX))
                .containsEntry("message.timestamp.type", "CreateTime");
    }

    @Test
    @DisplayName("retention.ms 는 업무와 .dlq 가 같다 — .dlq 를 짧게 두면 진단 원문이 좌표보다 먼저 사라진다")
    void dlqSharesRetentionMsWithBusiness() {
        assertThat(KafkaTopicConfigs.dlq(RETENTION, BEFORE_MAX).get("retention.ms"))
                .isEqualTo(KafkaTopicConfigs.business(RETENTION, BEFORE_MAX).get("retention.ms"));
    }

    // ── ADR-0020 §D4-1 확정값의 독립 정본 ──────────────────────────────────────
    // 여기 숫자는 SUT 의 상수를 읽지 않고 **ADR 본문에서 직접 옮겨 적은 것**이다.
    // SUT 상수를 기대값으로 재사용하면 8/4 → 10/2 같은 변경에서 "절반 관계"·"총 420 MiB"
    // 가 모두 유지되어 green 이 된다. 그래서 리터럴로 못박는다.

    private static final long ADR_BUSINESS_RETENTION_BYTES = 8L * 1024 * 1024;
    private static final long ADR_BUSINESS_SEGMENT_BYTES = 4L * 1024 * 1024;
    private static final long ADR_DLQ_RETENTION_BYTES = 4L * 1024 * 1024;
    private static final long ADR_DLQ_SEGMENT_BYTES = 2L * 1024 * 1024;
    private static final long ADR_SEGMENT_MS = Duration.ofDays(1).toMillis();

    @Test
    @DisplayName("업무 토픽 값이 ADR 확정값과 정확히 일치한다 (독립 정본 대조)")
    void businessMatchesAdrValues() {
        assertThat(KafkaTopicConfigs.business(RETENTION, BEFORE_MAX)).isEqualTo(Map.of(
                "retention.ms", String.valueOf(RETENTION.toMillis()),
                "cleanup.policy", "delete",
                "retention.bytes", String.valueOf(ADR_BUSINESS_RETENTION_BYTES),
                "segment.bytes", String.valueOf(ADR_BUSINESS_SEGMENT_BYTES),
                "segment.ms", String.valueOf(ADR_SEGMENT_MS),
                "message.timestamp.type", "CreateTime",
                "message.timestamp.before.max.ms", String.valueOf(BEFORE_MAX.toMillis())));
    }

    @Test
    @DisplayName(".dlq 값이 ADR 확정값과 정확히 일치한다 (독립 정본 대조)")
    void dlqMatchesAdrValues() {
        assertThat(KafkaTopicConfigs.dlq(RETENTION, BEFORE_MAX)).isEqualTo(Map.of(
                "retention.ms", String.valueOf(RETENTION.toMillis()),
                "cleanup.policy", "delete",
                "retention.bytes", String.valueOf(ADR_DLQ_RETENTION_BYTES),
                "segment.bytes", String.valueOf(ADR_DLQ_SEGMENT_BYTES),
                "segment.ms", String.valueOf(ADR_SEGMENT_MS),
                "message.timestamp.type", "CreateTime",
                "message.timestamp.before.max.ms", String.valueOf(BEFORE_MAX.toMillis())));
    }

    @Test
    @DisplayName("segment.bytes 는 retention.bytes 보다 작다 — active segment 가 상한 위에 얹히기 때문")
    void segmentIsSmallerThanRetentionBytes() {
        for (Map<String, String> configs : java.util.List.of(
                KafkaTopicConfigs.business(RETENTION, BEFORE_MAX),
                KafkaTopicConfigs.dlq(RETENTION, BEFORE_MAX))) {
            assertThat(Long.parseLong(configs.get("segment.bytes")))
                    .isLessThan(Long.parseLong(configs.get("retention.bytes")));
        }
    }

    /**
     * ADR-0020 §D4-2 의 용량 산정을 코드로 고정한다.
     *
     * <p>파티션의 실질 점유는 {@code retention.bytes} 가 아니라
     * <b>{@code retention.bytes + segment.bytes}</b> 다 — retention 은 닫힌 세그먼트만 지우고
     * active segment 는 그 위에 얹힌다. 현 토폴로지(업무 10토픽 × 3파티션 + dlq 10 × 1)에서
     * 약 420 MiB 이며, 이는 <b>hard bound 가 아니라 정상상태 목표치</b>다.
     */
    @Test
    @DisplayName("도메인 토픽 정상상태 점유가 산정치(420 MiB)와 일치한다")
    void steadyStateFootprintMatchesAdrEstimate() {
        // SUT 상수가 아니라 위 ADR 리터럴로 계산한다 — SUT 를 읽으면 8/4 → 10/2 처럼
        // 합이 같아지는 변경에서 이 단언이 통과해 버린다.
        long businessPerPartition = ADR_BUSINESS_RETENTION_BYTES + ADR_BUSINESS_SEGMENT_BYTES;
        long dlqPerPartition = ADR_DLQ_RETENTION_BYTES + ADR_DLQ_SEGMENT_BYTES;
        assertThat(businessPerPartition * 10 * 3 + dlqPerPartition * 10 * 1)
                .isEqualTo(420L * 1024 * 1024);
    }

    @Test
    @DisplayName("null/비양수 입력은 거부한다")
    void rejectsNonPositiveDurations() {
        assertThatThrownBy(() -> KafkaTopicConfigs.business(null, BEFORE_MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaTopicConfigs.business(Duration.ZERO, BEFORE_MAX))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> KafkaTopicConfigs.dlq(RETENTION, Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
