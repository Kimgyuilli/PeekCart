package com.peekcart.global.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class OutboxEventRepositoryImpl implements OutboxEventRepository {

    private final OutboxEventJpaRepository outboxEventJpaRepository;

    @Override
    public OutboxEvent save(OutboxEvent outboxEvent) {
        return outboxEventJpaRepository.save(outboxEvent);
    }

    @Override
    public List<OutboxEvent> findPendingEvents(List<String> aggregateTypes, int limit) {
        return outboxEventJpaRepository.findPendingEvents(aggregateTypes, PageRequest.of(0, limit));
    }

    @Override
    public long countByStatusAndAggregateTypeIn(OutboxEventStatus status, List<String> aggregateTypes) {
        return outboxEventJpaRepository.countByStatusAndAggregateTypeIn(status, aggregateTypes);
    }
}
