package com.peekcart.global.kafka;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 토픽 config 계약의 <b>단일 출처</b> (ADR-0020 §D4-1).
 *
 * <p><b>왜 계약이 필요한가</b>: 지금까지 어떤 토픽에도 {@code retention.ms}·{@code cleanup.policy}·
 * {@code retention.bytes} 가 선언돼 있지 않았다. 그렇다고 값이 미정의였던 것은 아니다 —
 * {@code apache/kafka:3.8.1} 이미지의 {@code log.retention.hours=168}(7일)이 실효값이었다.
 * 즉 <b>동작은 이미 7일이었고 그것이 계약이 아니었을 뿐</b>이다. DLQ replay(ADR-0020)가
 * "기록된 좌표의 원본 레코드"를 읽는 순간 이 값은 검증 대상이 되어야 한다.
 *
 * <p><b>동작 변경 여부는 config 마다 다르다</b> — 완료 보고에서 뭉뚱그리면 false-green 이 된다:
 * <ul>
 *   <li>{@code retention.ms}·{@code cleanup.policy}·{@code message.timestamp.type} — <b>실효 불변</b>.
 *       이미 Apache 기본값과 같다.</li>
 *   <li>{@code retention.bytes}·{@code segment.bytes}·{@code segment.ms} — <b>동작 변경</b>.
 *       현재 {@code retention.bytes} 는 {@code -1}(무제한)이고 {@code segment.bytes} 는 1 GiB 라,
 *       시간 만료 전 <b>크기 기반 삭제가 새로 생긴다</b>.</li>
 * </ul>
 *
 * <p><b>{@code segment.bytes} 를 반드시 선언하는 이유</b>: {@code retention.bytes} 는
 * <b>닫힌 세그먼트만</b> 삭제 대상으로 삼고 active segment 는 그 위에 얹힌다. 이미지 기본값이
 * 1 GiB 이므로 선언하지 않으면 파티션 하나가 PVC 전체를 넘길 수 있다. 파티션의 실질 점유는
 * {@code retention.bytes + segment.bytes} 로 본다.
 *
 * <p><b>이 값들은 보장이 아니다</b>(ADR-0020 §D4-2). 도메인 토픽 정상상태를 약 420 MiB 로
 * 억제할 뿐이고, retention 검사 주기·{@code file.delete.delay.ms}·index/timeindex·대형 batch 가
 * 계산에 없다. {@code __consumer_offsets}(기본 50파티션)와 KRaft metadata 는 아예 bound 밖이다.
 * 따라서 "7일 안의 좌표는 반드시 읽을 수 있다"는 <b>보장하지 않으며</b>, replay 는 발행 직전
 * 좌표 검증(ADR-0020 §D5-1)을 반드시 거친다.
 */
public final class KafkaTopicConfigs {

    /** 업무 토픽 파티션당 보존 상한. */
    static final long BUSINESS_RETENTION_BYTES = 8L * 1024 * 1024;
    /** {@code .dlq} 는 예외 경로라 유입이 적다 — 업무의 절반. */
    static final long DLQ_RETENTION_BYTES = 4L * 1024 * 1024;

    static final long BUSINESS_SEGMENT_BYTES = 4L * 1024 * 1024;
    static final long DLQ_SEGMENT_BYTES = 2L * 1024 * 1024;

    /** 유입이 적어 크기로 롤되지 않는 파티션도 세그먼트를 닫아 삭제 대상이 되게 한다. */
    static final Duration SEGMENT_ROLL = Duration.ofDays(1);

    private KafkaTopicConfigs() {
    }

    /**
     * 업무 토픽의 config.
     *
     * @param retention          {@code app.idempotency.floor.kafka-topic-retention} 에서 온 값.
     *                           멱등 창 계산의 입력과 <b>같은 출처</b>여야 한다 — 두 곳에 따로 적으면 갈라진다.
     * @param timestampBeforeMax {@code app.idempotency.retention} 에서 온 값. replay 는 <b>원본 timestamp</b> 를
     *                           실어 발행하므로(ADR-0020 §D3), 이 값이 replay 대상의 나이보다 짧으면 적격 replay 가
     *                           broker 에게 거부된다. 하한({@code dlqReplayWindow + clockSkewBudget})은
     *                           {@code IdempotencyRetentionProperties} 의 fail-fast 가 이미 강제한다.
     */
    public static Map<String, String> business(Duration retention, Duration timestampBeforeMax) {
        return configs(retention, timestampBeforeMax, BUSINESS_RETENTION_BYTES, BUSINESS_SEGMENT_BYTES);
    }

    /** {@code <topic>.dlq} 의 config. {@code retention.ms} 는 업무와 동일하다 — 짧게 두면 원장이 좌표를 들고 있어도 진단 원문이 먼저 사라진다. */
    public static Map<String, String> dlq(Duration retention, Duration timestampBeforeMax) {
        return configs(retention, timestampBeforeMax, DLQ_RETENTION_BYTES, DLQ_SEGMENT_BYTES);
    }

    private static Map<String, String> configs(Duration retention, Duration timestampBeforeMax,
                                               long retentionBytes, long segmentBytes) {
        if (retention == null || retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("retention 은 양수여야 합니다: " + retention);
        }
        if (timestampBeforeMax == null || timestampBeforeMax.isNegative() || timestampBeforeMax.isZero()) {
            throw new IllegalArgumentException("timestampBeforeMax 는 양수여야 합니다: " + timestampBeforeMax);
        }
        Map<String, String> configs = new LinkedHashMap<>();
        configs.put("retention.ms", String.valueOf(retention.toMillis()));
        // compact 는 좌표 hole 을 만든다 — replay 가 요청 offset 이 아닌 다음 레코드를 읽게 된다(ADR-0020 §D5-1).
        configs.put("cleanup.policy", "delete");
        configs.put("retention.bytes", String.valueOf(retentionBytes));
        configs.put("segment.bytes", String.valueOf(segmentBytes));
        configs.put("segment.ms", String.valueOf(SEGMENT_ROLL.toMillis()));
        // LogAppendTime 이면 broker 가 append 시각으로 덮어써 replay 의 원본 timestamp 보존이 깨진다.
        // 기본값이 CreateTime 이지만, 의존하는 순간 계약이 되어야 한다.
        configs.put("message.timestamp.type", "CreateTime");
        configs.put("message.timestamp.before.max.ms", String.valueOf(timestampBeforeMax.toMillis()));
        return configs;
    }
}
