package com.peekcart.global.outbox;

import java.util.List;

public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent outboxEvent);

    List<OutboxEvent> findPendingEvents(int limit);

    long countByStatus(OutboxEventStatus status);
}
