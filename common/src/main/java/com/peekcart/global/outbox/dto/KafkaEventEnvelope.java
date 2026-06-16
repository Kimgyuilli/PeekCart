package com.peekcart.global.outbox.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;

public record KafkaEventEnvelope(
        String eventId,
        String eventType,
        LocalDateTime timestamp,
        Object payload,
        int schemaVersion
) {
    /**
     * 기존 4-인자 호출부 호환 생성자. {@code schemaVersion} 을 1 로 채운다 (ADR-0012 §46).
     */
    public KafkaEventEnvelope(String eventId, String eventType, LocalDateTime timestamp, Object payload) {
        this(eventId, eventType, timestamp, payload, 1);
    }

    /**
     * 역직렬화 진입점. {@code schemaVersion} 누락(구버전 메시지)을 1 로 정규화한다 —
     * 하위호환 메시지는 "알 수 없는 버전(0)" 이 아니라 v1 계약으로 취급한다 (ADR-0012 §46).
     */
    @JsonCreator
    static KafkaEventEnvelope fromJson(
            @JsonProperty("eventId") String eventId,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("timestamp") LocalDateTime timestamp,
            @JsonProperty("payload") Object payload,
            @JsonProperty("schemaVersion") Integer schemaVersion) {
        return new KafkaEventEnvelope(eventId, eventType, timestamp, payload,
                schemaVersion == null ? 1 : schemaVersion);
    }
}
