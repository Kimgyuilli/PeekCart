package com.peekcart.global.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Kafka 메시지 payload 에서 MDC 주입에 사용할 식별자(eventId, userId, orderId) 를 best-effort 추출한다.
 * <p>
 * 파싱 실패나 필드 누락은 silent 처리하고 {@link Optional#empty()} 를 반환한다.
 * 정식 검증은 Listener 본문의 {@link KafkaMessageParser} 가 담당한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MdcPayloadExtractor {

    private final ObjectMapper objectMapper;

    public Extracted extract(String message) {
        if (message == null || message.isBlank()) {
            return Extracted.EMPTY;
        }
        try {
            JsonNode root = objectMapper.readTree(message);
            String eventId = textOrNull(root, "eventId");
            JsonNode payload = root.get("payload");
            String userId = payload == null ? null : textOrNull(payload, "userId");
            String orderId = payload == null ? null : textOrNull(payload, "orderId");
            return new Extracted(eventId, userId, orderId);
        } catch (Exception e) {
            log.debug("MDC payload 추출 실패 — silent fallback (length={})", message.length());
            return Extracted.EMPTY;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        String text = value.asText();
        return text.isBlank() ? null : text;
    }

    public record Extracted(String eventId, String userId, String orderId) {
        public static final Extracted EMPTY = new Extracted(null, null, null);
    }
}
