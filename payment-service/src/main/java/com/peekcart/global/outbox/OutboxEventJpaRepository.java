package com.peekcart.global.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    // 공유 DB 전환기 소유권 분리(Product peel): poller 별 aggregateType allowlist 로 자기 도메인 이벤트만 발행한다.
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'PENDING' AND o.aggregateType IN :aggregateTypes ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPendingEvents(List<String> aggregateTypes, Pageable pageable);

    // 공유 DB 전환기 backlog gauge 도 자기 소유 aggregateType 만 집계한다(발행 경로와 동일 소유권 분리).
    long countByStatusAndAggregateTypeIn(OutboxEventStatus status, List<String> aggregateTypes);
}
