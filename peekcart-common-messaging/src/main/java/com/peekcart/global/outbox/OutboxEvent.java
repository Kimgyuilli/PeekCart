package com.peekcart.global.outbox;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.function.Function;

@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent {

    /**
     * replay 레코드의 {@code event_type} 자리에 들어가는 sentinel (ADR-0020 D3 · 구현 ④-c-2b-2 P10).
     *
     * <p>여기에 목적지 토픽을 넣지 않는다. 구버전 poller 는 {@code event_type} 을 <b>그대로 목적지 토픽으로</b>
     * 쓰므로(kind 분기가 없던 판본), 롤백 시 실제 업무 토픽 이름이 이 자리에 있으면 <b>원장 id 를 key 로
     * fence 없이 발행</b>된다 — 파티션도 원본과 다르다. sentinel 이면 존재하지 않는 토픽으로 향해 눈에 띄게 실패한다.
     */
    public static final String REPLAY_EVENT_TYPE = "__replay__";

    /** replay 레코드의 aggregate_type. 도메인 집합과 섞이지 않게 별도 값을 쓴다. */
    public static final String REPLAY_AGGREGATE_TYPE = "DLQ_REPLAY";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", nullable = false, length = 50)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 50)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 50)
    private String eventType;

    @Column(name = "event_id", nullable = false, unique = true, length = 36)
    private String eventId;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxEventStatus status;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "last_attempted_at")
    private LocalDateTime lastAttemptedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    @Column(name = "user_id", length = 64)
    private String userId;

    // --- replay 발행 축 (ADR-0020 D3 · 구현 ④-c-2b-2 P10) ---

    /**
     * 레코드 종류 판별자. {@code null} 은 <b>구버전 writer 가 쓴 행</b>이라는 뜻이며 도메인으로 해석한다.
     * DB 에 DEFAULT 를 두지 않는 이유는 {@link #isReplay()} 주석 참조.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "record_kind", length = 10)
    private OutboxRecordKind recordKind;

    @Column(name = "destination_topic", length = 120)
    private String destinationTopic;

    @Column(name = "destination_partition")
    private Integer destinationPartition;

    @Column(name = "record_key", length = 255)
    private String recordKey;

    @Column(name = "source_record_timestamp")
    private Long sourceRecordTimestamp;

    @Column(name = "replay_target_event_id", length = 36)
    private String replayTargetEventId;

    @Column(name = "replay_headers", columnDefinition = "TEXT")
    private String replayHeaders;

    @Column(name = "replay_root_record_id")
    private Long replayRootRecordId;

    @Column(name = "target_consumer_group", length = 120)
    private String targetConsumerGroup;

    public static OutboxEvent create(String aggregateType, String aggregateId,
                                     String eventType,
                                     String traceId, String userId,
                                     Function<String, String> payloadFactory) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.eventType = eventType;
        event.eventId = UUID.randomUUID().toString();
        event.payload = payloadFactory.apply(event.eventId);
        event.status = OutboxEventStatus.PENDING;
        event.retryCount = 0;
        event.createdAt = LocalDateTime.now();
        event.traceId = traceId;
        event.userId = userId;
        // 명시한다 — DB DEFAULT 에 기대면 discriminator 누락이 조용히 DOMAIN 으로 분류된다 (ADR-0020 D3).
        event.recordKind = OutboxRecordKind.DOMAIN;
        return event;
    }

    /**
     * DLQ replay 레코드를 만든다 (ADR-0020 D3 · D8-3 · 구현 ④-c-2b-2 P10).
     *
     * <p>발행 자격 검사(fence 6종)는 <b>이 팩토리의 책임이 아니다</b> — 호출자(진입점, 구현 ④-c-2b-4)가
     * 원장 행과 원본 레코드를 대조한 뒤에만 부른다. 여기서는 좌표를 그대로 싣는 일만 한다.
     *
     * @param ledgerRecordId  원장 행 id. {@code aggregate_id}(NOT NULL) 를 채운다
     * @param targetEventId   재발행 대상 payload 안의 eventId. {@code event_id}(= 이 attempt 의 UUID)와 다른 축이다
     */
    public static OutboxEvent replay(Long ledgerRecordId,
                                     String destinationTopic, Integer destinationPartition,
                                     String recordKey, String payload,
                                     Long sourceRecordTimestamp,
                                     String targetEventId, String replayHeaders,
                                     Long replayRootRecordId, String targetConsumerGroup,
                                     String traceId, String userId) {
        OutboxEvent event = new OutboxEvent();
        event.aggregateType = REPLAY_AGGREGATE_TYPE;
        event.aggregateId = String.valueOf(ledgerRecordId);
        event.eventType = REPLAY_EVENT_TYPE;
        event.eventId = UUID.randomUUID().toString();
        event.payload = payload;
        event.status = OutboxEventStatus.PENDING;
        event.retryCount = 0;
        event.createdAt = LocalDateTime.now();
        event.traceId = traceId;
        event.userId = userId;
        event.recordKind = OutboxRecordKind.REPLAY;
        event.destinationTopic = destinationTopic;
        event.destinationPartition = destinationPartition;
        event.recordKey = recordKey;
        event.sourceRecordTimestamp = sourceRecordTimestamp;
        event.replayTargetEventId = targetEventId;
        event.replayHeaders = replayHeaders;
        event.replayRootRecordId = replayRootRecordId;
        event.targetConsumerGroup = targetConsumerGroup;
        return event;
    }

    /**
     * replay 레코드인가. <b>{@code null} 은 replay 가 아니다</b> — 구버전 writer 가 만든 행은 도메인이다
     * (ADR-0020 D3 expand 단계). 이 해석을 {@code recordKind == DOMAIN} 으로 좁히면 마이그레이션 직후의
     * 기존 PENDING 행이 어느 분기에도 걸리지 않아 <b>영원히 발행되지 않는다</b>.
     */
    public boolean isReplay() {
        return recordKind == OutboxRecordKind.REPLAY;
    }

    public void markPublished() {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = LocalDateTime.now();
    }

    public void incrementRetry() {
        this.retryCount++;
        this.lastAttemptedAt = LocalDateTime.now();
    }

    public void markFailed() {
        this.status = OutboxEventStatus.FAILED;
        this.lastAttemptedAt = LocalDateTime.now();
    }
}
