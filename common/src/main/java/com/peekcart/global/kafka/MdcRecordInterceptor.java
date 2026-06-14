package com.peekcart.global.kafka;

import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.MDC;
import org.springframework.kafka.listener.RecordInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Kafka Consumer 진입 시 MDC 에 traceId/userId/orderId 를 주입하고, record 처리 종료 시 정리한다.
 * <p>
 * traceId 우선순위:
 * <ol>
 *   <li>{@link KafkaTraceHeaders#TRACE_ID} 헤더 (Producer 측 trace context 영속화 후 — D-010)</li>
 *   <li>payload 의 {@code eventId} (재시도/DLQ 경로까지 동일 ID 로 묶임)</li>
 *   <li>신규 UUID (방어적 fallback)</li>
 * </ol>
 * userId 는 {@link KafkaTraceHeaders#USER_ID} 헤더 우선, 없으면 payload 에서 추출.
 * orderId 는 도메인 식별자이므로 payload 에서만 추출(헤더 전파 대상 아님).
 * <p>
 * MDC 정리는 {@link #afterRecord(ConsumerRecord, Consumer)} 에서만 수행한다.
 * Spring Kafka 호출 순서상 {@code failure()} 직후 {@code CommonErrorHandler} 가 실행되며
 * (예: {@link com.peekcart.global.config.KafkaConfig} 의 DLQ 발행/Slack 알림 로그),
 * {@code afterRecord} 는 그 이후 finally 단계에 호출되므로
 * 장애 경로 로그(DLQ 발행, error handler) 까지 MDC 가 살아 있어야 한다.
 */
@RequiredArgsConstructor
public class MdcRecordInterceptor implements RecordInterceptor<String, String> {

    static final String MDC_TRACE_ID = "traceId";
    static final String MDC_USER_ID = "userId";
    static final String MDC_ORDER_ID = "orderId";

    private final MdcPayloadExtractor payloadExtractor;

    @Override
    public ConsumerRecord<String, String> intercept(ConsumerRecord<String, String> record,
                                                    Consumer<String, String> consumer) {
        MdcPayloadExtractor.Extracted extracted = payloadExtractor.extract(record.value());

        String traceId = headerValue(record, KafkaTraceHeaders.TRACE_ID);
        if (traceId == null) {
            traceId = extracted.eventId();
        }
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(MDC_TRACE_ID, traceId);

        String userId = headerValue(record, KafkaTraceHeaders.USER_ID);
        if (userId == null) {
            userId = extracted.userId();
        }
        if (userId != null) {
            MDC.put(MDC_USER_ID, userId);
        }

        if (extracted.orderId() != null) {
            MDC.put(MDC_ORDER_ID, extracted.orderId());
        }

        return record;
    }

    @Override
    public void afterRecord(ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
        MDC.remove(MDC_TRACE_ID);
        MDC.remove(MDC_USER_ID);
        MDC.remove(MDC_ORDER_ID);
    }

    private static String headerValue(ConsumerRecord<String, String> record, String key) {
        Header header = record.headers().lastHeader(key);
        if (header == null || header.value() == null) {
            return null;
        }
        String value = new String(header.value(), StandardCharsets.UTF_8);
        return value.isBlank() ? null : value;
    }
}
