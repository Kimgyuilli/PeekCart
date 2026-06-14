package com.peekcart.global.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer 메시지 파싱 유틸리티.
 * {@code KafkaEventEnvelope} 구조({@code eventId}, {@code payload})를 검증하고 파싱한다.
 */
@Component
@RequiredArgsConstructor
public class KafkaMessageParser {

    private final ObjectMapper objectMapper;

    /**
     * Kafka 메시지를 파싱하여 {@code eventId}와 {@code payload} 필드를 검증한 뒤 root {@link JsonNode}를 반환한다.
     *
     * @param message Kafka 메시지 (JSON 문자열)
     * @return 파싱된 root JsonNode
     * @throws IllegalArgumentException 역직렬화 실패 또는 필수 필드 누락 시
     */
    public JsonNode parse(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            if (root.get("eventId") == null) {
                throw new IllegalArgumentException("Kafka 메시지에 eventId 필드가 없습니다 (length=" + message.length() + ")");
            }
            if (root.get("payload") == null) {
                throw new IllegalArgumentException("Kafka 메시지에 payload 필드가 없습니다 (length=" + message.length() + ")");
            }
            return root;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Kafka 메시지 역직렬화 실패", e);
        }
    }
}
