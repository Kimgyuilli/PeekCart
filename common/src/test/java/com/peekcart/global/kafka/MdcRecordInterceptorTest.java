package com.peekcart.global.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.record.TimestampType;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MdcRecordInterceptor — traceId fallback 및 MDC 정리")
class MdcRecordInterceptorTest {

    private final MdcPayloadExtractor extractor = new MdcPayloadExtractor(new ObjectMapper());
    private final MdcRecordInterceptor interceptor = new MdcRecordInterceptor(extractor);

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @DisplayName("헤더 X-Trace-Id 가 있으면 traceId 로 사용한다")
    void traceId_from_header() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-1\",\"payload\":{\"userId\":42,\"orderId\":7}}");
        record.headers().add(KafkaTraceHeaders.TRACE_ID, "header-trace".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, null);

        assertThat(MDC.get("traceId")).isEqualTo("header-trace");
        assertThat(MDC.get("userId")).isEqualTo("42");
        assertThat(MDC.get("orderId")).isEqualTo("7");
    }

    @Test
    @DisplayName("헤더가 없으면 payload.eventId 를 traceId 로 사용한다 (재시도/DLQ 경로 묶기 위함)")
    void traceId_falls_back_to_eventId() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-abc\",\"payload\":{\"orderId\":99}}");

        interceptor.intercept(record, null);

        assertThat(MDC.get("traceId")).isEqualTo("evt-abc");
        assertThat(MDC.get("orderId")).isEqualTo("99");
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("헤더도 eventId 도 없으면 신규 UUID 16자리를 traceId 로 발급한다")
    void traceId_falls_back_to_uuid_when_payload_invalid() {
        ConsumerRecord<String, String> record = recordWith("not a json");

        interceptor.intercept(record, null);

        String traceId = MDC.get("traceId");
        assertThat(traceId).isNotNull().hasSize(16).matches("[0-9a-f]{16}");
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
    }

    @Test
    @DisplayName("X-Trace-Id 헤더가 blank(빈 문자열) 이면 payload.eventId 로 fallback 한다")
    void traceId_falls_back_when_header_blank() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-blank\",\"payload\":{\"orderId\":3}}");
        record.headers().add(KafkaTraceHeaders.TRACE_ID, "".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, null);

        assertThat(MDC.get("traceId")).isEqualTo("evt-blank");
        assertThat(MDC.get("orderId")).isEqualTo("3");
        assertThat(MDC.get("userId")).isNull();
    }

    @Test
    @DisplayName("X-Trace-Id 만 헤더에 있고 X-User-Id 부재 → traceId 는 헤더값, userId 는 null")
    void traceId_from_header_userId_missing() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-x\",\"payload\":{\"orderId\":5}}");
        record.headers().add(KafkaTraceHeaders.TRACE_ID, "header-trace".getBytes(StandardCharsets.UTF_8));

        interceptor.intercept(record, null);

        assertThat(MDC.get("traceId")).isEqualTo("header-trace");
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("orderId")).isEqualTo("5");
    }

    @Test
    @DisplayName("payload 의 userId/orderId 가 없으면 MDC 에 설정하지 않는다")
    void optional_fields_skipped_when_absent() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-2\",\"payload\":{\"orderNumber\":\"O-1\"}}");

        interceptor.intercept(record, null);

        assertThat(MDC.get("traceId")).isEqualTo("evt-2");
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
    }

    @Test
    @DisplayName("afterRecord 호출 후에 MDC 가 비워진다")
    void mdc_cleared_after_record() {
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-3\",\"payload\":{\"userId\":1,\"orderId\":2}}");
        interceptor.intercept(record, null);
        assertThat(MDC.get("traceId")).isEqualTo("evt-3");
        assertThat(MDC.get("userId")).isEqualTo("1");
        assertThat(MDC.get("orderId")).isEqualTo("2");

        interceptor.afterRecord(record, null);
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
    }

    @Test
    @DisplayName("listener 예외 후 error handler 실행 시점(=afterRecord 직전)까지 MDC 가 살아있다")
    void mdc_survives_until_after_record_in_failure_path() {
        // Spring Kafka 호출 순서: intercept → listener throw → failure → CommonErrorHandler → afterRecord
        // CommonErrorHandler 의 DLQ 발행/Slack 알림 로그(KafkaConfig.kafkaErrorHandler) 가
        // MDC 컨텍스트를 누리도록, failure() 시점에서는 MDC 를 지우면 안 된다.
        ConsumerRecord<String, String> record = recordWith(
                "{\"eventId\":\"evt-fail\",\"payload\":{\"userId\":7,\"orderId\":11}}");
        interceptor.intercept(record, null);

        // failure() 는 default no-op — MDC 그대로 유지되어야 함
        interceptor.failure(record, new RuntimeException("boom"), null);
        assertThat(MDC.get("traceId"))
                .as("error handler 가 traceId 를 로그에 남길 수 있어야 함")
                .isEqualTo("evt-fail");
        assertThat(MDC.get("userId")).isEqualTo("7");
        assertThat(MDC.get("orderId")).isEqualTo("11");

        // afterRecord() 시점에야 정리
        interceptor.afterRecord(record, null);
        assertThat(MDC.get("traceId")).isNull();
        assertThat(MDC.get("userId")).isNull();
        assertThat(MDC.get("orderId")).isNull();
    }

    private static ConsumerRecord<String, String> recordWith(String value) {
        return new ConsumerRecord<>(
                "test.topic", 0, 0L, 0L, TimestampType.NO_TIMESTAMP_TYPE,
                0, value == null ? 0 : value.length(),
                null, value, new RecordHeaders(), java.util.Optional.empty());
    }
}
