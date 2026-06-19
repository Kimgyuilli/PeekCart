package com.peekcart.global.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Kafka Consumer 멱등성 보장을 위한 처리 이력 엔티티.
 * {@code (event_id, consumer_group)} 복합 UK로 동일 이벤트의 중복 소비를 방지한다.
 */
@Entity
@Table(name = "processed_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_processed_event_consumer",
                columnNames = {"event_id", "consumer_group"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, length = 36)
    private String eventId;

    @Column(name = "consumer_group", nullable = false, length = 100)
    private String consumerGroup;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    /**
     * 처리 이력을 생성한다.
     *
     * @param eventId       Kafka 메시지의 이벤트 ID (UUID)
     * @param consumerGroup Kafka Consumer Group ID
     * @return 생성된 처리 이력
     */
    public static ProcessedEvent create(String eventId, String consumerGroup) {
        ProcessedEvent event = new ProcessedEvent();
        event.eventId = eventId;
        event.consumerGroup = consumerGroup;
        event.processedAt = LocalDateTime.now();
        return event;
    }
}
