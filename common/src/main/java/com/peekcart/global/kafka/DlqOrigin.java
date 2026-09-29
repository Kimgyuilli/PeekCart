package com.peekcart.global.kafka;

/**
 * DLQ 레코드 1건에서 뽑아낸 원장 적재 입력.
 *
 * <p><b>좌표 3종은 항상 채워진다.</b> {@code originKind} 가 {@link DlqOriginKind#RESOLVED_ORIGIN}
 * 이면 원본 토픽의 좌표, {@link DlqOriginKind#DLQ_ORIGIN} 이면 DLQ 레코드 자신의 좌표다.
 * "판독 불가면 NULL" 은 UNIQUE 를 무력화하므로 쓰지 않는다.
 *
 * <p><b>{@code failedConsumerGroup} 도 항상 채워진다.</b> 헤더가 없으면
 * {@link #UNKNOWN_CONSUMER_GROUP} sentinel 이 들어가며, 그런 레코드는 quarantine 전용
 * listener 만 적재한다.
 *
 * @param originKind         좌표의 출처
 * @param originTopic        좌표 — 토픽
 * @param originPartition    좌표 — 파티션
 * @param originOffset       좌표 — offset
 * @param failedConsumerGroup 실패한 consumer group. 부재 시 {@link #UNKNOWN_CONSUMER_GROUP}
 * @param originalKey        원본 메시지 key. <b>헤더가 아니라 DLQ 레코드 자신의 key</b> 에서 온다. null 가능
 * @param originalTimestamp  원본 메시지 timestamp (epoch millis). 헤더 부재 시 null
 * @param exceptionType      실패 예외 FQCN. 헤더 부재 시 null
 * @param exceptionMessage   실패 예외 메시지. 헤더 부재 시 null
 * @param payload            원본 메시지 본문. null 가능(tombstone)
 * @param replayAttemptId    replay 상관 — 시도 UUID. 헤더 부재·판독 실패 시 null
 * @param replayLedgerOwner  replay 상관 — 원장 소유 서비스 prefix. 헤더 부재 시 null
 * @param replayTargetGroup  replay 상관 — 표적 consumer group. 헤더 부재 시 null
 * @param replayRootId       replay 상관 — canonical root 의 원장 행 id. 헤더 부재·파싱 실패 시 null
 */
public record DlqOrigin(
        DlqOriginKind originKind,
        String originTopic,
        int originPartition,
        long originOffset,
        String failedConsumerGroup,
        String originalKey,
        Long originalTimestamp,
        String exceptionType,
        String exceptionMessage,
        String payload,

        // --- replay 상관 (ADR-0021) ---
        // 전부 nullable 이다. 이 넷은 조작 가능한 application 헤더이므로 **지시자일 뿐 판정 근거가 아니다** —
        // 실제 판정은 원장 앵커와의 대조가 한다. 여기서 null 이면 상관 시도조차 하지 않는다.
        String replayAttemptId,
        String replayLedgerOwner,
        String replayTargetGroup,
        Long replayRootId
) {

    /**
     * {@code kafka_dlt-original-consumer-group} 헤더가 없을 때 쓰는 sentinel.
     *
     * <p>NULL 을 쓰지 않는 이유는 {@link DlqOriginKind} 와 같다 — UNIQUE 구성 컬럼이기 때문이다.
     * 이 값을 가진 레코드의 소유자는 "원본 토픽을 발행하는 서비스" 하나로 고정된다.
     */
    public static final String UNKNOWN_CONSUMER_GROUP = "__unknown__";

    public DlqOrigin {
        if (originKind == null) {
            throw new IllegalArgumentException("originKind 는 필수입니다");
        }
        if (originTopic == null || originTopic.isBlank()) {
            throw new IllegalArgumentException("originTopic 은 필수입니다");
        }
        if (failedConsumerGroup == null || failedConsumerGroup.isBlank()) {
            throw new IllegalArgumentException("failedConsumerGroup 은 필수입니다 (부재 시 UNKNOWN_CONSUMER_GROUP)");
        }
    }

    /**
     * replay 재발행분으로 <b>주장하는</b> 레코드인가.
     *
     * <p><b>"주장" 이다.</b> 네 헤더가 다 있어도 그것만으로는 replay 가 아니다 — 원장 앵커 대조를 통과해야
     * 상관된다(ADR-0021 §D1). 이 메서드는 <b>대조를 시도할 가치가 있는지</b>만 가른다.
     */
    public boolean claimsReplay() {
        return replayAttemptId != null && replayLedgerOwner != null
                && replayTargetGroup != null && replayRootId != null;
    }

    /** consumer group 을 판독하지 못한 레코드인가. quarantine listener 의 적재 조건이다. */
    public boolean hasUnknownConsumerGroup() {
        return UNKNOWN_CONSUMER_GROUP.equals(failedConsumerGroup);
    }
}
