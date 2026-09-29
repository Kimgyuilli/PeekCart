package com.peekcart.global.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * {@link ProcessedEvent} JPA Repository.
 */
public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventIdAndConsumerGroup(String eventId, String consumerGroup);

    /**
     * retention cleanup 배치 삭제 (ADR-0012 D5 · 구현 ② PR3).
     * {@code processed_at} 이 {@code cutoff} 이전인 행을 최대 {@code limit} 건 삭제한다.
     * 자기 트랜잭션으로 실행돼(스케줄러 메서드는 비-트랜잭션) 각 batch 가 독립 커밋된다.
     *
     * @return 삭제된 행 수
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM processed_events WHERE processed_at < :cutoff LIMIT :limit",
            nativeQuery = true)
    int deleteBatchOlderThan(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
