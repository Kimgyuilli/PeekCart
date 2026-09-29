package com.peekcart.global.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;
import org.springframework.kafka.support.KafkaHeaders;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * DLQ 레코드에서 {@link DlqOrigin} 을 뽑는다.
 *
 * <p><b>커스텀 헤더를 만들지 않는다.</b> consumer group 헤더도 직접 주입할 필요가
 * 없다 — {@code spring-kafka-3.3.14} 의 {@code DeadLetterPublishingRecoverer} 는
 * {@code whichHeaders = EnumSet.allOf(HeadersToAdd.class)} 가 기본값이라 {@code GROUP} 을 포함해
 * 전 헤더를 붙이며, 값은 {@code ListenerExecutionFailedException#getGroupId()} 에서 온다.
 *
 * <p><b>입력이 {@link Headers} 가 아니라 {@link ConsumerRecord} 인 이유</b>: 원본 key 를 담는
 * {@code DLT_ORIGINAL_KEY} 헤더는 <b>존재하지 않는다</b>({@code DLT_*} 13개 중 없음.
 * {@code DLT_KEY_EXCEPTION_*} 는 key 역직렬화 예외 정보다). 원본 key 는 recoverer 가 만든
 * DLQ 레코드 자신의 key 로 보존되므로 {@code record.key()} 에서 읽어야 한다.
 *
 * <p>헤더 인코딩은 recoverer 구현과 맞춘다 — topic·group 은 UTF-8, partition 은 4바이트 int,
 * offset·timestamp 는 8바이트 long (big-endian).
 *
 * <p><b>표준 {@code DLT_*} 와 {@link ReplayHeaders} allowlist 4종만 판독하고, 그 밖의 application 헤더는
 * 제외한다</b>. 제외 목록을 관리하지 않아도 되도록 <b>읽을 키를 명시</b>하는 구조다 —
 * {@code X-User-Id} 같은 헤더는 애초에 {@link DlqOrigin} 에 들어오지 않는다.
 *
 * <p><b>replay 헤더가 재실패 시에도 살아남는 근거</b>: {@code spring-kafka-3.3.14} 의
 * {@code DeadLetterPublishingRecoverer.accept()} 는 {@code new RecordHeaders(record.headers().toArray())} 로
 * <b>원본 헤더 전량을 복사한 뒤</b> {@code DLT_*} 를 얹는다(바이트코드 실측).
 * {@code stripPreviousExceptionHeaders}(기본 true)는 예외 헤더만 지운다.
 */
public final class DlqHeaders {

    private DlqHeaders() {
    }

    /**
     * DLQ 레코드를 원장 적재 입력으로 변환한다.
     *
     * <p>origin 헤더 3종(topic·partition·offset)을 전부 판독하면 {@link DlqOriginKind#RESOLVED_ORIGIN},
     * 하나라도 없거나 깨졌으면 DLQ 레코드 자신의 좌표를 쓰는 {@link DlqOriginKind#DLQ_ORIGIN} 이 된다.
     * <b>예외를 던지지 않는다</b> — 판독 실패는 적재 대상이지 유실 사유가 아니다.
     */
    public static DlqOrigin parse(ConsumerRecord<String, String> record) {
        Headers headers = record.headers();

        String originTopic = readString(headers, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        Integer originPartition = readInt(headers, KafkaHeaders.DLT_ORIGINAL_PARTITION);
        Long originOffset = readLong(headers, KafkaHeaders.DLT_ORIGINAL_OFFSET);

        boolean originResolved = originTopic != null && !originTopic.isBlank()
                && originPartition != null && originOffset != null;

        String group = readString(headers, KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP);
        if (group == null || group.isBlank()) {
            group = DlqOrigin.UNKNOWN_CONSUMER_GROUP;
        }

        return new DlqOrigin(
                originResolved ? DlqOriginKind.RESOLVED_ORIGIN : DlqOriginKind.DLQ_ORIGIN,
                originResolved ? originTopic : record.topic(),
                originResolved ? originPartition : record.partition(),
                originResolved ? originOffset : record.offset(),
                group,
                record.key(),
                readLong(headers, KafkaHeaders.DLT_ORIGINAL_TIMESTAMP),
                readString(headers, KafkaHeaders.DLT_EXCEPTION_FQCN),
                readString(headers, KafkaHeaders.DLT_EXCEPTION_MESSAGE),
                record.value(),
                readString(headers, ReplayHeaders.ATTEMPT_ID),
                readString(headers, ReplayHeaders.LEDGER_OWNER),
                readString(headers, ReplayHeaders.TARGET_GROUP),
                readRootId(headers)
        );
    }

    /**
     * {@code pc-replay-root-id} 를 long 으로 읽는다. <b>파싱 실패는 null 이다 — 예외를 던지지 않는다.</b>
     *
     * <p>이 값은 <b>조작 가능한 외부 입력</b>이다. 숫자가 아닌 값에 예외를 던지면 <b>DLQ 적재 자체가 막혀
     * 실패 사실이 유실</b>된다 — 누구든 헤더 하나로 원장 적재를 무력화할 수 있게 된다.
     * 판독 실패는 "상관하지 않음" 이지 "적재하지 않음" 이 아니다.
     *
     * <p>UTF-8 문자열로 읽는 이유는 발행 측이 그렇게 싣기 때문이다 —
     * {@code OutboxPollingService} 는 {@code replay_headers} JSON 의 값을 UTF-8 로만 인코딩한다.
     */
    private static Long readRootId(Headers headers) {
        String raw = readString(headers, ReplayHeaders.ROOT_ID);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String readString(Headers headers, String key) {
        byte[] value = lastValue(headers, key);
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }

    /** 4바이트가 아니면 판독 실패로 본다 — 짧은 배열에 {@code getInt} 를 쓰면 예외가 난다. */
    private static Integer readInt(Headers headers, String key) {
        byte[] value = lastValue(headers, key);
        return (value == null || value.length != Integer.BYTES) ? null : ByteBuffer.wrap(value).getInt();
    }

    private static Long readLong(Headers headers, String key) {
        byte[] value = lastValue(headers, key);
        return (value == null || value.length != Long.BYTES) ? null : ByteBuffer.wrap(value).getLong();
    }

    /**
     * 같은 키가 여러 번 있으면 마지막 값을 쓴다.
     * {@code appendOriginalHeaders} 가 기본 true 라 재-DLQ 시 헤더가 누적될 수 있고,
     * 그때 유효한 것은 가장 최근 값이다.
     */
    private static byte[] lastValue(Headers headers, String key) {
        byte[] last = null;
        for (Header header : headers.headers(key)) {
            last = header.value();
        }
        return last;
    }
}
