package com.peekcart.global.outbox;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface OutboxEventJpaRepository extends JpaRepository<OutboxEvent, Long> {

    // DB-per-service(구현 ② PR2): 자기 스키마의 outbox_events 만 보이므로 allowlist 없이 자기 PENDING 전체 조회.
    @Query("SELECT o FROM OutboxEvent o WHERE o.status = 'PENDING' ORDER BY o.createdAt ASC")
    List<OutboxEvent> findPendingEvents(Pageable pageable);

    long countByStatus(OutboxEventStatus status);
}
