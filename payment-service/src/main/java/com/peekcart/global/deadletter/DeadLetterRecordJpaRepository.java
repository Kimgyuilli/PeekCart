package com.peekcart.global.deadletter;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DeadLetterRecordJpaRepository extends JpaRepository<DeadLetterRecord, Long> {

    /**
     * 원장 적재 (계획 ④-c-2a P6). <b>단일 원자 INSERT</b> — 유니크 충돌을 예외가 아니라
     * 영향 행 수 0 으로 돌려받아야 소비 트랜잭션이 rollback-only 로 오염되지 않는다.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id = id} 를 쓰지 않는 이유(④-c-1a 실측): MySQL Connector/J 는
     * 기본이 <b>found-rows</b> 시맨틱이라 값이 바뀌지 않은 중복도 <b>1</b> 로 보고한다 — 그러면
     * 중복 유입에도 "신규 적재" 로 판단해 알림이 중복 발송된다. {@code INSERT IGNORE} 는 건너뛴 행을
     * 0 으로 보고한다.
     *
     * @return 1 이면 신규 적재(알림 대상), 0 이면 이미 있음(no-op)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO dead_letter_records
                (cluster_id, topic_generation, origin_topic, origin_partition, origin_offset,
                 failed_consumer_group, origin_kind, event_id, original_key, original_timestamp,
                 payload, payload_truncated, exception_type, exception_message,
                 status, attempt_count, occurred_at)
            VALUES (:clusterId, :topicGeneration, :originTopic, :originPartition, :originOffset,
                    :failedConsumerGroup, :originKind, :eventId, :originalKey, :originalTimestamp,
                    :payload, :payloadTruncated, :exceptionType, :exceptionMessage,
                    'OPEN', 1, :occurredAt)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("clusterId") String clusterId,
                       @Param("topicGeneration") int topicGeneration,
                       @Param("originTopic") String originTopic,
                       @Param("originPartition") int originPartition,
                       @Param("originOffset") long originOffset,
                       @Param("failedConsumerGroup") String failedConsumerGroup,
                       @Param("originKind") String originKind,
                       @Param("eventId") String eventId,
                       @Param("originalKey") String originalKey,
                       @Param("originalTimestamp") Long originalTimestamp,
                       @Param("payload") String payload,
                       @Param("payloadTruncated") boolean payloadTruncated,
                       @Param("exceptionType") String exceptionType,
                       @Param("exceptionMessage") String exceptionMessage,
                       @Param("occurredAt") LocalDateTime occurredAt);

    /**
     * 같은 좌표가 다시 유입됐을 때 시도 횟수만 올린다. 새 행을 만들지 않는다 —
     * 같은 사실이 여러 행이면 미결 건수가 부풀어 운영 판단이 틀어진다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE dead_letter_records
               SET attempt_count = attempt_count + 1
             WHERE cluster_id = :clusterId AND topic_generation = :topicGeneration
               AND origin_topic = :originTopic AND origin_partition = :originPartition
               AND origin_offset = :originOffset AND failed_consumer_group = :failedConsumerGroup
            """, nativeQuery = true)
    int incrementAttempt(@Param("clusterId") String clusterId,
                         @Param("topicGeneration") int topicGeneration,
                         @Param("originTopic") String originTopic,
                         @Param("originPartition") int originPartition,
                         @Param("originOffset") long originOffset,
                         @Param("failedConsumerGroup") String failedConsumerGroup);

    Optional<DeadLetterRecord> findByClusterIdAndTopicGenerationAndOriginTopicAndOriginPartitionAndOriginOffsetAndFailedConsumerGroup(
            String clusterId, int topicGeneration, String originTopic,
            int originPartition, long originOffset, String failedConsumerGroup);

    /**
     * 미결 <b>incident</b> 건수 (actuator 조회 표면 · 건수 상한 경보).
     *
     * <p><b>집계 단위는 행이 아니라 root 다</b>(ADR-0020 §D6-3). 재발행이 실패할 때마다 자식 행이 늘어나므로
     * 행 단위로 세면 backlog 가 사건 수보다 계속 부풀고, "재실패해도 미결은 1건" 이 거짓이 된다.
     *
     * <p><b>{@code root_record_id IS NULL} 을 함께 받는 것이 전환 구간 계약이다</b> — 이 컬럼은 additive 라
     * ④-c-2a 가 적재한 기존 행이 전부 NULL 이다. 조건을 {@code = id} 로만 걸면 <b>기존 미결이 전부 탈락해
     * backlog 가 0 으로 보인다</b>. backfill(④-c-2b-4 P23) 이후에도 이 분기는 남는다.
     */
    @Query("SELECT COUNT(r) FROM DeadLetterRecord r "
            + "WHERE (r.rootRecordId IS NULL OR r.rootRecordId = r.id) AND r.status IN ('OPEN', 'ACKED')")
    long countUnresolved();

    /** 가장 오래된 미결 incident 의 발생 시각 (age 경보). 없으면 empty. */
    @Query("SELECT MIN(r.occurredAt) FROM DeadLetterRecord r "
            + "WHERE (r.rootRecordId IS NULL OR r.rootRecordId = r.id) AND r.status IN ('OPEN', 'ACKED')")
    Optional<LocalDateTime> findOldestUnresolvedOccurredAt();

    /** age 임계값을 넘긴 미결 incident (경보 대상). */
    @Query("SELECT r FROM DeadLetterRecord r "
            + "WHERE (r.rootRecordId IS NULL OR r.rootRecordId = r.id) AND r.status IN ('OPEN', 'ACKED') "
            + "AND r.occurredAt < :threshold ORDER BY r.occurredAt ASC")
    List<DeadLetterRecord> findStaleUnresolved(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    /**
     * 미결 incident 중 <b>replay 를 요청한 적 없는</b> 건수 (actuator 요약).
     *
     * <p>{@code publication_status IS NULL} 은 ADR-0020 §D6-1 표의 정식 상태("요청 없음")이며
     * 현 단계에서는 <b>사실상 전부</b>가 여기 해당한다. 이 값을 빼고 enum 3값만 노출하면
     * 미결이 있는데도 발행 분포가 전부 0 인 오해를 부른다.
     */
    @Query("SELECT COUNT(r) FROM DeadLetterRecord r "
            + "WHERE (r.rootRecordId IS NULL OR r.rootRecordId = r.id) AND r.status IN ('OPEN', 'ACKED') "
            + "AND r.publicationStatus IS NULL")
    long countUnresolvedWithoutPublication();

    /**
     * 미결 incident 중 발행 축이 특정 상태인 건수 (actuator 요약).
     * <b>{@code PUBLISHED} 도 미결에 포함된다</b> — 발행 성공은 사건 해소가 아니다(ADR-0020 §D6-2).
     */
    @Query("SELECT COUNT(r) FROM DeadLetterRecord r "
            + "WHERE (r.rootRecordId IS NULL OR r.rootRecordId = r.id) AND r.status IN ('OPEN', 'ACKED') "
            + "AND r.publicationStatus = :publicationStatus")
    long countUnresolvedByPublicationStatus(@Param("publicationStatus") PublicationStatus publicationStatus);

    /**
     * 요청 id 가 속한 <b>canonical root 의 id</b>. 없으면 empty.
     *
     * <p><b>엔티티가 아니라 id 만 돌려주는 것이 계약이다.</b> 엔티티로 읽으면 그 인스턴스가 영속성
     * 컨텍스트에 적재되고, 뒤이은 {@link #findByIdForUpdate}(SELECT ... FOR UPDATE)는 <b>DB 잠금은 얻지만
     * 이미 관리 중인 인스턴스의 상태를 refresh 하지 않는다</b>. 그러면 잠금을 기다리는 동안 다른
     * 트랜잭션이 커밋한 최신 상태를 못 보고 <b>캐시의 과거 상태를 기준으로 전이</b>하게 된다 —
     * "이미 terminal 이면 no-op" 계약이 그 경로에서 깨진다.
     */
    @Query("SELECT CASE WHEN r.rootRecordId IS NULL THEN r.id ELSE r.rootRecordId END "
            + "FROM DeadLetterRecord r WHERE r.id = :id")
    Optional<Long> findRootIdOf(@Param("id") Long id);

    /**
     * replay attempt-id 로 그 시도를 개시한 <b>canonical root 의 id</b>. 없으면 empty
     * (계획 ④-c-2b-3b P15 단계 1 — 로케이터).
     *
     * <p><b>대조가 아니라 탐색이다.</b> 여기서 못 찾으면 상관 자체를 시도하지 않고 독립 root 로 적재한다.
     * attempt 값의 <b>대조</b>는 잠금 후 단 한 곳(P15 단계 3)에서만 한다 — 관측점을 늘리면
     * "predicate 하나를 지우면 정확히 그 행만 red" 가 attempt 축에서 성립하지 않는다.
     *
     * <p><b>{@link #findRootIdOf} 와 같은 이유로 id 만 돌려준다</b> — 엔티티로 읽으면 그 인스턴스가
     * 영속성 컨텍스트에 적재돼 뒤이은 {@code FOR UPDATE} 가 잠금만 얻고 상태를 refresh 하지 않는다.
     *
     * <p><b>canonical root 로 한정한다</b>({@code rootRecordId IS NULL OR = id}). 자식 행에 앵커가
     * 실릴 일은 없지만 조건을 좁혀 둔다 — 앵커의 writer 는 replay 진입점 하나이고 대상은 root 다.
     */
    @Query("SELECT r.id FROM DeadLetterRecord r "
            + "WHERE r.lastReplayAttemptId = :attemptId "
            + "AND (r.rootRecordId IS NULL OR r.rootRecordId = r.id)")
    Optional<Long> findRootIdByReplayAttemptId(@Param("attemptId") String attemptId);

    /**
     * incident 의 <b>활성</b> 자식(재발행 재실패분)을 잠그고 읽는다. root 자신은 제외한다.
     *
     * <p><b>잠금이 정확성의 일부다.</b> 잠금 없는 조회는 MySQL REPEATABLE READ 에서 이 트랜잭션이
     * 처음 연 consistent-read <b>스냅샷</b>을 본다. root 는 {@code FOR UPDATE}(current read)로 최신을
     * 보는데 자식만 과거 스냅샷을 보면, 앞선 트랜잭션이 root+자식을 종결한 뒤 이 트랜잭션이
     * <b>root 에서는 no-op 하면서 자식만 덮어쓰는</b> 상태가 만들어진다 — ADR-0020 §D5-4 의
     * "root 와 활성 자식에 원자적 전파" 가 그 경로에서 깨진다.
     *
     * <p><b>활성({@code OPEN}/{@code ACKED})만 잠근다.</b> 호출부가 terminal 자식을 어차피 건너뛰므로
     * 전부 잠그면 재개방·재실패가 반복돼 과거 terminal 자식이 쌓일수록 <b>쓰지도 않을 행까지 배타
     * 잠금</b>해 대기 집합만 커진다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DeadLetterRecord r WHERE r.rootRecordId = :rootId AND r.id <> :rootId "
            + "AND r.status IN ('OPEN', 'ACKED')")
    List<DeadLetterRecord> findChildrenForUpdate(@Param("rootId") Long rootId);

    /**
     * 종결 전이·purge 를 위해 root 를 잠근다.
     *
     * <p>종결 전파(P5)·재개방(④-c-2b-3 P15)·purge(P4)가 <b>전부 이 잠금을 먼저 잡는다</b> —
     * 진입 순서가 같아야 순환이 생기지 않는다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM DeadLetterRecord r WHERE r.id = :id")
    Optional<DeadLetterRecord> findByIdForUpdate(@Param("id") Long id);

    /**
     * 종결 incident 의 정리 대상 <b>root</b>. {@code OPEN}/{@code ACKED} 는 대상이 아니다 —
     * 장기 미결은 용량 문제가 아니라 운영 SLA 문제이고, 지우면 그 사실이 사라진다(§2.6-E).
     *
     * <p><b>{@code COALESCE(discarded_at, resolved_at)} 를 쓰지 않는다</b>: {@code DISCARDED} → 재개방 →
     * {@code RESOLVED} 를 거친 root 는 <b>두 시각을 모두</b> 갖고, {@code COALESCE} 는 항상 과거의
     * {@code discarded_at} 을 골라 <b>새 종결의 보존기간이 지나기 전에 삭제</b>한다. 현재 상태에 해당하는
     * 시각만 본다. 감사 시각 자체는 재개방 시에도 지우지 않는다.
     */
    @Query("SELECT r.id FROM DeadLetterRecord r "
            + "WHERE (r.rootRecordId IS NULL OR r.rootRecordId = r.id) "
            + "AND ((r.status = 'DISCARDED' AND r.discardedAt < :threshold) "
            + "  OR (r.status = 'RESOLVED' AND r.resolvedAt < :threshold)) "
            + "ORDER BY r.id ASC")
    List<Long> findPurgeableRootIds(@Param("threshold") LocalDateTime threshold, Pageable pageable);

    /**
     * 발행 축이 {@code REQUESTED} 인 행을 오래된 순으로 읽는다 (ADR-0020 §D6-4 · 구현 ④-c-2b-2 P12).
     *
     * <p>reconciler 전용이다. <b>root 로 한정하지 않는다</b> — 발행은 행 단위 사실이고, 자식도 자기
     * replay 요청을 가질 수 있다. incident 집계(사건 축)만 root 로 정규화한다.
     *
     * <p>인덱스를 따로 두지 않았다(계획 §10 R8). 이 테이블은 DLQ 유입량에 유계이고, 같은 컬럼을 스캔하는
     * {@link #countUnresolvedByPublicationStatus} 가 이미 무인덱스로 돈다.
     */
    @Query("SELECT r FROM DeadLetterRecord r WHERE r.publicationStatus = 'REQUESTED' ORDER BY r.id ASC")
    List<DeadLetterRecord> findRequestedPublications(Pageable pageable);

    /**
     * incident 1건(root + 자식 전부)을 삭제한다. <b>자식 단독 purge 경로는 두지 않는다</b> —
     * 자식은 진단용이며 root 를 따라 종결·정리된다(ADR-0020 §D6-3).
     *
     * <p>{@code r.id = :rootId} 를 함께 두는 이유: 전환 구간의 기존 root 는 {@code root_record_id} 가
     * {@code NULL} 이라 {@code rootRecordId = :rootId} 조건에 자기 자신이 걸리지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM DeadLetterRecord r WHERE r.id = :rootId OR r.rootRecordId = :rootId")
    int deleteIncident(@Param("rootId") Long rootId);
}
