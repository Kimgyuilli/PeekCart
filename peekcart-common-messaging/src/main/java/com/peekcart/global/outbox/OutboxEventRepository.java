package com.peekcart.global.outbox;

import java.time.LocalDateTime;
import java.util.List;

public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent outboxEvent);

    /** 발행된 이벤트들을 한 문장·한 트랜잭션으로 PUBLISHED 로 기록하고 바뀐 행 수를 돌려준다 (ADR-0049). */
    int markPublished(List<Long> ids, LocalDateTime publishedAt);

    List<OutboxEvent> findPendingEvents(int limit);

    long countByStatus(OutboxEventStatus status);
}
