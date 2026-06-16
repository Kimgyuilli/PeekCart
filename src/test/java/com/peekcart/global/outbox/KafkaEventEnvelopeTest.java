package com.peekcart.global.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("KafkaEventEnvelope schemaVersion 하위호환 테스트")
class KafkaEventEnvelopeTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("기존 4-인자 생성자는 schemaVersion 을 1 로 채운다")
    void legacyConstructor_defaultsSchemaVersionToOne() {
        KafkaEventEnvelope envelope = new KafkaEventEnvelope(
                "evt-1", "order.created", LocalDateTime.now(), "payload");

        assertThat(envelope.schemaVersion()).isEqualTo(1);
    }

    @Test
    @DisplayName("직렬화 시 schemaVersion 필드가 포함된다")
    void serialize_includesSchemaVersion() throws Exception {
        KafkaEventEnvelope envelope = new KafkaEventEnvelope(
                "evt-1", "order.created", LocalDateTime.now(), "payload");

        String json = objectMapper.writeValueAsString(envelope);

        assertThat(json).contains("\"schemaVersion\":1");
    }

    @Test
    @DisplayName("schemaVersion 없는 구버전 메시지는 v1 으로 정규화되어 역직렬화된다")
    void deserialize_legacyMessageWithoutSchemaVersion_normalizesToV1() throws Exception {
        String legacy = "{\"eventId\":\"evt-1\",\"eventType\":\"order.created\","
                + "\"timestamp\":\"2026-01-01T00:00:00\",\"payload\":\"p\"}";

        KafkaEventEnvelope envelope = objectMapper.readValue(legacy, KafkaEventEnvelope.class);

        assertThat(envelope.eventId()).isEqualTo("evt-1");
        assertThat(envelope.eventType()).isEqualTo("order.created");
        assertThat(envelope.schemaVersion()).isEqualTo(1);
    }
}
