package com.peekcart.global.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * DLQ payload 를 <b>관대하게</b> 읽는다.
 *
 * <p>{@link KafkaMessageParser} 는 {@code eventId}/{@code payload} 부재를 예외로 던진다 —
 * 그건 소비 경로의 계약이라 옳다. 하지만 <b>그 예외 때문에 DLQ 로 온 메시지</b>를 원장에 적재할 때
 * 같은 파서를 쓰면 적재 자체가 실패한다. DLQ 경로는 "읽히면 쓰고 아니면 null" 이어야 한다.
 */
public final class DlqPayloads {

    private DlqPayloads() {
    }

    /**
     * payload 에서 {@code eventId} 를 best-effort 로 뽑는다.
     * JSON 이 아니거나 필드가 없으면 {@code null} — <b>예외를 던지지 않는다</b>.
     *
     * <p>원장에서 {@code eventId} 는 보조 검색키일 뿐 식별자가 아니므로 없어도 적재에 지장이 없다.
     */
    public static String extractEventId(ObjectMapper objectMapper, String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode eventId = root.get("eventId");
            if (eventId == null || !eventId.isTextual()) {
                return null;
            }
            String value = eventId.asText();
            return value.isBlank() ? null : value;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 저장 상한에 맞춰 자른다.
     *
     * @return 잘렸으면 {@code truncated=true}
     */
    public static Truncation truncate(String payload, int maxLength) {
        if (payload == null || payload.length() <= maxLength) {
            return new Truncation(payload, false);
        }
        return new Truncation(payload.substring(0, maxLength), true);
    }

    /** @param value 저장할 값 · @param truncated 잘렸는지 */
    public record Truncation(String value, boolean truncated) {
    }
}
