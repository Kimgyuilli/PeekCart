package com.peekcart.global.kafka;

/**
 * Kafka 메시지 trace context 전파에 사용하는 헤더 키 상수.
 * <p>
 * 현재는 Consumer 측에서만 읽지만, Outbox trace context 영속화(D-010) 도입 시
 * Producer 측에서 동일 키로 헤더를 주입하여 end-to-end 추적이 가능하도록 forward-compatible 하게 정의한다.
 */
public final class KafkaTraceHeaders {

    public static final String TRACE_ID = "X-Trace-Id";
    public static final String USER_ID = "X-User-Id";

    private KafkaTraceHeaders() {
    }
}
