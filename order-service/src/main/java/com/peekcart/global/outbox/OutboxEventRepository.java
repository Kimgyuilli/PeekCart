package com.peekcart.global.outbox;

import java.util.List;

public interface OutboxEventRepository {

    OutboxEvent save(OutboxEvent outboxEvent);

    List<OutboxEvent> findPendingEvents(List<String> aggregateTypes, int limit);

    long countByStatusAndAggregateTypeIn(OutboxEventStatus status, List<String> aggregateTypes);
}
