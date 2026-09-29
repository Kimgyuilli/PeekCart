package com.peekcart.global.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    // DB-per-service: 자기 스키마의 outbox_events 만 보이므로 allowlist 없이 자기 PENDING 전체 조회.
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'PENDING' ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPendingEvents(Pageable pageable);

    long countByStatus(OutboxEventStatus status);

    /**
     * retention cleanup 배치 삭제 (ADR-0012 D5).
     * <b>PUBLISHED 상태 + {@code published_at < cutoff}</b> 인 행만 최대 {@code limit} 건 삭제한다.
     * PENDING·FAILED 는 status 조건으로, {@code published_at IS NULL} 은 {@code < cutoff} 비교(NULL 미매치)로
     * 자연히 보존된다(미발행/실패 유실 금지). 자기 트랜잭션으로 각 batch 가 독립 커밋된다.
     *
     * <p><b>연결된 원장 root 가 아직 {@code REQUESTED} 인 replay 행은 제외한다</b> (ADR-0020 D6-4).
     * reconciler 가 retention 이상 멈추면 replay outbox 가 먼저 삭제되고, 원장은 {@code REQUESTED} 에
     * 영구 고착된다 — 발행 축이 미결인 행은 종결도 막혀 있어 사람도 닫지 못한다. 원장과 outbox 는
     * <b>같은 서비스 DB</b>(DB-per-service)라 한 문장 안에서 대조할 수 있다.
     *
     * <p>상관 참조에 alias 대신 <b>정규명</b>을 쓰는 것은 문법 제약 때문이 아니다 — MySQL 8.0.16+ 는 단일 테이블
     * DELETE 에도 alias 를 허용한다(실측 확인).
     * 다만 alias 를 <b>선언 없이</b> 참조하면 {@code ERROR 1054} 이고, alias 를 쓰는 multi-table 형식
     * ({@code DELETE o FROM ...})은 {@code LIMIT} 을 지원하지 않아 배치 상한이 사라진다.
     *
     * @return 삭제된 행 수
     */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
             WHERE status = 'PUBLISHED' AND published_at < :cutoff
               AND NOT EXISTS (SELECT 1 FROM dead_letter_records d
                                WHERE d.outbox_event_id = outbox_events.id
                                  AND d.publication_status = 'REQUESTED')
             LIMIT :limit
            """,
            nativeQuery = true)
    int deletePublishedBatchOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /**
     * reconciler 가 원장의 발행 축을 전이시킬 때 읽는 outbox 상태 (ADR-0020 D6-4).
     *
     * <p>엔티티가 아니라 <b>상태만</b> 반환한다 — reconciler 는 payload 를 볼 이유가 없고,
     * 엔티티를 영속성 컨텍스트에 올리면 의도치 않은 dirty checking 대상이 된다.
     * 행이 없으면 {@code Optional.empty()} 이며, <b>그것을 실패로 해석하지 않는다</b>(ADR-0020 §D6-4).
     */
    @Query("SELECT o.status FROM OutboxEvent o WHERE o.id = :id")
    Optional<OutboxEventStatus> findStatusById(@Param("id") Long id);
}
