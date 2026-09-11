package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * DLQ 원장 (계획 ④-c-2a P3). "DLQ 로 빠졌다"를 <b>영속</b> 사실로 남긴다.
 *
 * <p>기존에는 {@code kafkaErrorHandler} 의 로그와 Slack 알림뿐이었다. 알림은 휘발성이라
 * <b>미결 건이 아직 남아있는지 판정할 수 없다</b> — 그게 이 테이블의 존재 이유다.
 *
 * <p><b>물리 식별자는 6컬럼이며 전부 NOT NULL 이다</b>(§2.5). {@code origin_kind} 가
 * {@link DlqOriginKind#RESOLVED_ORIGIN} 이면 좌표는 원본 토픽의 것이고,
 * {@link DlqOriginKind#DLQ_ORIGIN} 이면 DLQ 레코드 자신의 것이다. "판독 불가면 NULL" 로 두면
 * MySQL UNIQUE 가 NULL 끼리 충돌시키지 않아 같은 poison record 가 여러 행이 된다.
 *
 * <p>{@code cluster_id}/{@code topic_generation} 이 키에 들어간 이유: {@code (topic, partition,
 * offset, group)} 은 <b>토픽 재생성 시 유일하지 않다</b>. 동명 재생성이면 offset 이 0부터 재사용되어
 * 과거 행과 충돌하고, {@code INSERT IGNORE} 때문에 <b>새 실패가 정상 중복처럼 조용히 폐기</b>된다.
 *
 * <p><b>{@code payload} 는 진단용이다.</b> 상한 초과 시 절단되며 {@code payload_truncated} 로 표시한다.
 * 원본 복원(replay)은 ④-c-2b 소관이고 그 원본은 이 컬럼이 아니라 원본 토픽의 좌표에서 읽는다.
 */
@Entity
@Table(name = "dead_letter_records")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DeadLetterRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // --- 물리 식별자 6종 (UNIQUE, 전부 NOT NULL) ---

    @Column(name = "cluster_id", nullable = false, length = 60)
    private String clusterId;

    @Column(name = "topic_generation", nullable = false)
    private int topicGeneration;

    @Column(name = "origin_topic", nullable = false, length = 120)
    private String originTopic;

    @Column(name = "origin_partition", nullable = false)
    private int originPartition;

    @Column(name = "origin_offset", nullable = false)
    private long originOffset;

    @Column(name = "failed_consumer_group", nullable = false, length = 120)
    private String failedConsumerGroup;

    // --- 좌표의 출처 ---

    @Enumerated(EnumType.STRING)
    @Column(name = "origin_kind", nullable = false, length = 20)
    private DlqOriginKind originKind;

    // --- 진단 정보 ---

    /** 원본 메시지의 eventId. 파싱 불가·필드 부재면 null — 이 값은 검색 보조키일 뿐 식별자가 아니다. */
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Column(name = "original_key", length = 255)
    private String originalKey;

    @Column(name = "original_timestamp")
    private Long originalTimestamp;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "payload_truncated", nullable = false)
    private boolean payloadTruncated;

    @Column(name = "exception_type", length = 255)
    private String exceptionType;

    @Column(name = "exception_message", length = 2000)
    private String exceptionMessage;

    // --- incident 축 (④-c-2b-1, ADR-0020 §D6-3) ---

    /**
     * canonical incident root 의 id. <b>{@code root_record_id = id} 인 행이 root</b> 이고,
     * 다른 값이면 replay 재실패로 생긴 자식이다.
     *
     * <p><b>{@code NULL} 은 "root" 로 해석한다.</b> ④-c-2a 가 적재한 기존 행은 이 컬럼이 없었기 때문이다.
     * 집계 조건을 곧바로 {@code root_record_id = id} 로 걸면 <b>기존 미결이 전부 탈락해 backlog 가 0</b> 이
     * 된다 — 자식 과다집계를 고치려다 정확히 반대 방향의 false-green 을 만드는 경로다(§D6-3 전환 구간).
     */
    @Column(name = "root_record_id")
    private Long rootRecordId;

    // --- 발행 축 (④-c-2b-1, ADR-0020 §D6-1) ---

    /** {@code NULL} = replay 요청 없음. 어느 값도 terminal 이 아니다 — 발행 성공은 사건 해소가 아니다. */
    @Enumerated(EnumType.STRING)
    @Column(name = "publication_status", length = 20)
    private PublicationStatus publicationStatus;

    /**
     * 이 행의 replay 요청이 만든 {@code outbox_events.id} (④-c-2b-2 P12, ADR-0020 §D6-4).
     *
     * <p>컬럼 자체는 ④-c-2b-1 이 만들었으나 <b>읽는 주체가 없어 매핑하지 않았다</b>. reconciler 가
     * 처음으로 읽는다. FK 를 걸지 않는 것은 원장 행이 outbox retention 보다 오래 살기 때문이다 —
     * 정상 경로에서 outbox 행이 먼저 사라지는 것을 cleanup 제외 조건이 막지만, 그것은 제약이 아니라 정책이다.
     */
    @Column(name = "outbox_event_id")
    private Long outboxEventId;

    // --- replay 상관 앵커 (④-c-2b-3a P14-d, ADR-0021 §D1) ---

    /**
     * 이 root 가 가장 최근에 개시한 replay 시도의 UUID. <b>대조의 정본은 outbox 가 아니라 여기다.</b>
     *
     * <p>{@code outbox_events.record_kind} 로 대조하지 않는 이유(ADR-0021 §D1): {@code PUBLISHED} outbox 행은
     * retention 후 삭제되는데 <b>미결 root 는 무기한 남는다</b> — 수명 경쟁에서 져서 정상 attempt 가
     * 대조에 실패하고 독립 incident 로 갈라진다.
     *
     * <p>컬럼은 ④-c-2b-1 V8 이 만들었고, <b>읽는 주체가 없어 매핑하지 않았다</b>. 여기서 처음 매핑한다.
     */
    @Column(name = "last_replay_attempt_id", length = 36)
    private String lastReplayAttemptId;

    /** 그 시도가 표적한 업무 consumer group. 실제 실패 group·헤더와 3자 대조한다. */
    @Column(name = "last_replay_target_group", length = 120)
    private String lastReplayTargetGroup;

    /**
     * 재발행 대상 payload <b>전문</b>의 SHA-256 hex (④-c-2b-3a P14-e).
     *
     * <p><b>{@link #payload} 컬럼으로 대조할 수 없어서 따로 둔다.</b> 그 값은 {@code maxLength} 로 잘려
     * 저장되므로 상한 밖 변조를 통과시킨다 — "byte-for-byte 동일"(ADR-0020 §D8-3)을 주장할 수 없다.
     *
     * <p>이 값이 없으면 같은 {@code eventId}·key·timestamp 를 실은 <b>변조 payload</b> 가 좌표 대조를 전부
     * 통과해 <b>남의 사건에 자식으로 붙고 종결된 root 를 재개방</b>한다.
     *
     * <p><b>writer 는 replay 진입점 하나다</b>(④-c-2b-4 P21) — 원본 토픽에서 실제로 읽어온 payload 로 계산한다.
     * 3a 는 컬럼과 매핑만 만들고 값을 쓰지 않는다.
     */
    @Column(name = "last_replay_payload_digest", length = 64)
    private String lastReplayPayloadDigest;

    /**
     * 이 root 의 발행 축이 <b>가장 최근에 종착한 시각</b> (④-c-2b-4a P23 · 계획 §10.1 #6).
     *
     * <p>롤백 전 drain 판정 ⓓ("재발행분의 소비 재시도가 끝났는가")의 <b>내구적 기준시각</b>이다.
     * {@code PUBLISHED} 뿐 아니라 <b>{@code PUBLISH_FAILED} 로 종착할 때도</b> 찍는다 — poller 는 broker
     * ack 를 받은 뒤 상태 저장을 따로 하므로(crash window, ADR-0020 §D1), 저장 실패로 재시도가 소진되면
     * <b>이미 전달된 행이 최종 {@code FAILED}</b> 가 된다. 즉 {@code PUBLISH_FAILED} 는 "발행되지 않았다"
     * 의 증명이 아니라 <b>"발행 여부를 모른다"</b> 이므로 보수적으로 기록한다.
     *
     * <p><b>쓰기는 {@link DeadLetterRecordJpaRepository#stampReplaySettledAt} 하나뿐이고 DB 시각을 쓴다</b> —
     * 앱 시각으로 찍으면 drain 비교 기준({@code NOW()})과 갈라진다.
     */
    @Column(name = "last_replay_settled_at", insertable = false, updatable = false)
    private LocalDateTime lastReplaySettledAt;

    /**
     * 마지막 replay 요청의 <b>감사 주체</b> (④-c-2b-4a · diff 리뷰 2R #2).
     *
     * <p>진입점이 <b>인증 principal</b> 로 만든 값이며 요청자가 보낸 메모가 괄호로 따라붙는다.
     * 로그에만 남기면 프로세스 로그가 사라진 뒤 누가 승인·거부했는지 원장에서 복원할 수 없다 —
     * {@code acknowledged_by}/{@code resolved_by} 와 같은 계약이다. <b>거부도 기록한다.</b>
     */
    @Column(name = "last_replay_by", length = 160)
    private String lastReplayBy;

    // --- replay 정책 축 (④-c-2b-1 V8 이 컬럼 생성, ④-c-2b-4a 가 처음 매핑) ---

    /**
     * 멱등 안전창의 종료 시각 (ADR-0020 §D5-3). {@code original_timestamp + dlq-replay-window} 이며
     * <b>root 에서 1회 계산하고 자식·재시도가 상속한다</b> — 재계산하면 실패할 때마다 창이 연장된다.
     *
     * <p><b>drain 판정과 무관하다</b> — 그 축은 {@link #lastReplaySettledAt} 이 진다. 두 의미를 한 컬럼에
     * 담으려던 초안은 계획 리뷰에서 반증됐다(7d 안전창이 초 단위로 축소된다).
     */
    @Column(name = "replay_deadline")
    private LocalDateTime replayDeadline;

    /** 적격성 판정의 감사 기록 — {@code 정책식별자:버전:판정}. deny 도 남긴다(거부 이력이 사라지면 안 된다). */
    @Column(name = "replay_policy", length = 120)
    private String replayPolicy;

    // --- 상태 ---

    /**
     * {@link DeadLetterStatus} 의 name. enum 매핑이 아니라 문자열인 이유는 ④-c-2b 가
     * {@code RESOLVED} 를 <b>마이그레이션 없이</b> 추가할 수 있게 하기 위함이다(§2.6-A).
     * 실제로 ④-c-2b-1 이 그 확장점을 썼다.
     */
    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "acknowledged_by", length = 120)
    private String acknowledgedBy;

    @Column(name = "discarded_at")
    private LocalDateTime discardedAt;

    @Column(name = "discarded_by", length = 120)
    private String discardedBy;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @Column(name = "resolved_by", length = 120)
    private String resolvedBy;

    // --- 재개방 (④-c-2b-3a P14-d 매핑 · 전이는 ④-c-2b-3b P15, ADR-0020 §D6-2b I-2) ---

    /**
     * 종결됐던 root 가 늦은 자식 때문에 다시 열린 시각.
     *
     * <p><b>{@link #resolvedAt}/{@link #discardedAt} 는 지우지 않는다</b> — 감사 이력이고, purge 는
     * <b>현재 상태에 해당하는 시각만</b> 본다({@code findPurgeableRootIds} 의 상태별 분기).
     */
    @Column(name = "reopened_at")
    private LocalDateTime reopenedAt;

    @Column(name = "reopened_reason", length = 500)
    private String reopenedReason;

    @Column(name = "note", length = 1000)
    private String note;

    private DeadLetterRecord(String clusterId, int topicGeneration, DlqOrigin origin,
                             String eventId, String payload, boolean payloadTruncated) {
        this.clusterId = clusterId;
        this.topicGeneration = topicGeneration;
        this.originKind = origin.originKind();
        this.originTopic = origin.originTopic();
        this.originPartition = origin.originPartition();
        this.originOffset = origin.originOffset();
        this.failedConsumerGroup = origin.failedConsumerGroup();
        this.eventId = eventId;
        this.originalKey = origin.originalKey();
        this.originalTimestamp = origin.originalTimestamp();
        this.payload = payload;
        this.payloadTruncated = payloadTruncated;
        this.exceptionType = origin.exceptionType();
        this.exceptionMessage = origin.exceptionMessage();
        this.status = DeadLetterStatus.OPEN.name();
        this.attemptCount = 1;
        // 저장소 정밀도(DATETIME(6))로 맞춰 기록한다 — MySQL 은 초과 자릿수를 반올림하므로
        // 나노초를 그대로 두면 인메모리 값과 저장된 값이 최대 1μs 어긋난다 (④-c-1b 전례).
        this.occurredAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
    }

    public static DeadLetterRecord open(String clusterId, int topicGeneration, DlqOrigin origin,
                                        String eventId, String payload, boolean payloadTruncated) {
        return new DeadLetterRecord(clusterId, topicGeneration, origin, eventId, payload, payloadTruncated);
    }

    public DeadLetterStatus statusValue() {
        return DeadLetterStatus.valueOf(status);
    }

    /** 이 행이 canonical incident root 인가. {@code NULL} 은 root 로 본다(전환 구간). */
    public boolean isRoot() {
        return rootRecordId == null || rootRecordId.equals(id);
    }

    /**
     * 자기 자신을 root 로 지정한다. 신규 적재 직후 1회만 호출된다.
     *
     * <p><b>{@code LAST_INSERT_ID()} 를 쓰지 않는 이유</b>: {@code INSERT IGNORE} 가 중복으로 건너뛰면
     * 그 함수는 <b>직전 성공 INSERT 의 값</b>을 그대로 돌려줘 남의 행을 root 로 지목한다.
     */
    public void assignSelfRoot() {
        if (this.rootRecordId == null) {
            this.rootRecordId = this.id;
        }
    }

    /**
     * 이 행을 다른 incident 의 <b>자식</b>으로 잇는다. 재발행 재실패분을 원래 사건에 귀속시키는
     * 경로(④-c-2b-3 P15)가 쓴다 — 자식은 backlog 에 세지 않고 root 를 따라 종결·정리된다.
     */
    public void linkToRoot(Long rootId) {
        this.rootRecordId = rootId;
    }

    /**
     * 해소를 확인했음을 기록한다. <b>근거가 없으면 거부한다</b> — {@code RESOLVED} 는 "무엇을 보고
     * 해소를 확인했는지"(도메인 상태 조회 결과 등)가 남아야 {@code DISCARDED} 와 구분된다.
     *
     * <p>이 전이는 <b>사람만</b> 개시한다. broker ack 로는 도달하지 않는다(ADR-0020 §D6-2).
     *
     * @return 실제로 전이했으면 true
     */
    public boolean resolve(String actor, String evidence) {
        if (evidence == null || evidence.isBlank()) {
            throw new IllegalArgumentException("RESOLVED 는 해소를 확인한 근거 기록이 필수입니다");
        }
        if (statusValue().isTerminal()) {
            return false;
        }
        this.status = DeadLetterStatus.RESOLVED.name();
        this.resolvedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.resolvedBy = actor;
        this.note = evidence;
        return true;
    }

    /**
     * 운영자가 확인했음을 기록한다. 이미 종결된 건은 no-op — 종결을 되돌리지 않는다.
     *
     * @return 실제로 전이했으면 true
     */
    /**
     * 발행 축을 종착 상태로 옮긴다 (ADR-0020 §D6-4 · 구현 ④-c-2b-2 P12).
     *
     * <p><b>{@code REQUESTED} 인 행만 전이한다.</b> 이미 {@code PUBLISHED}/{@code PUBLISH_FAILED} 인 행을
     * 다시 덮으면 reconciler 가 재실행될 때마다 감사 사실이 바뀐다. {@code NULL}(요청 없음) 행은
     * 애초에 reconciler 의 조회 대상이 아니지만, 방어적으로 같은 가드에 걸린다.
     *
     * <p><b>사건 축({@link #status})은 건드리지 않는다</b> — 발행 성공은 사건 해소가 아니다(§D6-2).
     * 두 축을 물리적으로 분리한 이유가 여기서 지켜진다.
     *
     * @return 실제로 전이했으면 true, 이미 전이된 행이면 false(no-op)
     */
    public boolean settlePublication(PublicationStatus settled) {
        if (publicationStatus != PublicationStatus.REQUESTED) {
            return false;
        }
        if (settled != PublicationStatus.PUBLISHED && settled != PublicationStatus.PUBLISH_FAILED) {
            throw new IllegalArgumentException("발행 축의 종착 상태가 아니다: " + settled);
        }
        this.publicationStatus = settled;
        return true;
    }

    public boolean acknowledge(String actor) {
        if (statusValue() != DeadLetterStatus.OPEN) {
            return false;
        }
        this.status = DeadLetterStatus.ACKED.name();
        this.acknowledgedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.acknowledgedBy = actor;
        return true;
    }

    /**
     * 재처리하지 않기로 종결한다. <b>사유가 없으면 거부한다</b> — 근거 없이 닫힌 원장은
     * "해결됨" 과 구분되지 않아 거짓말을 한다.
     *
     * @return 실제로 전이했으면 true
     */
    /**
     * 종결된 incident 를 다시 연다 (계획 ④-c-2b-3b P15-d, ADR-0020 §D6-2b I-2).
     *
     * <p><b>이 전이만 사람이 아니라 시스템이 개시한다.</b> 운영자가 닫은 사건을 되돌리는 유일한 경로이므로
     * 운영 알림 대상이고({@code DeadLetterMetrics} 의 재개방 Counter), 근거를 반드시 남긴다.
     *
     * <p><b>{@code resolvedAt}/{@code discardedAt} 을 지우지 않는다</b> — 감사 이력이다. purge 는
     * <b>현재 상태에 해당하는 시각만</b> 보므로({@code findPurgeableRootIds} 의 status 분기)
     * {@code OPEN} 으로 돌아온 행은 그 시각이 남아 있어도 삭제 후보에서 빠진다.
     *
     * <p><b>terminal 인 행만 전이한다.</b> 이미 {@code OPEN}/{@code ACKED} 인 root 에 자식이 붙는 것은
     * 정상이고 재개방이 아니다 — 그 경우까지 재개방으로 세면 알림과 Counter 가 사건을 과대 보고한다.
     *
     * @return 실제로 재개방했으면 true, terminal 이 아니어서 no-op 이면 false
     */
    public boolean reopen(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("재개방은 사유 기록이 필수입니다");
        }
        if (!statusValue().isTerminal()) {
            return false;
        }
        this.status = DeadLetterStatus.OPEN.name();
        this.reopenedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.reopenedReason = reason;
        return true;
    }

    public boolean discard(String actor, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("DISCARDED 는 사유 기록이 필수입니다");
        }
        if (statusValue().isTerminal()) {
            return false;
        }
        this.status = DeadLetterStatus.DISCARDED.name();
        this.discardedAt = LocalDateTime.now().truncatedTo(ChronoUnit.MICROS);
        this.discardedBy = actor;
        this.note = reason;
        return true;
    }
}
