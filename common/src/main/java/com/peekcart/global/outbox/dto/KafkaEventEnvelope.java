package com.peekcart.global.outbox.dto;

import java.time.LocalDateTime;

public record KafkaEventEnvelope(
        String eventId,
        String eventType,
        LocalDateTime timestamp,
        Object payload
) {
}
