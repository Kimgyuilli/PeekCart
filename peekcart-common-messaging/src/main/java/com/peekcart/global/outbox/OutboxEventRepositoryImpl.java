package com.peekcart.global.outbox;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
    public int markPublished(List<Long> ids, LocalDateTime publishedAt) {
        return outboxEventJpaRepository.markPublished(ids, publishedAt);
    }

    @Override
    public List<OutboxEvent> findPendingEvents(int limit) {
        return outboxEventJpaRepository.findPendingEvents(PageRequest.of(0, limit));
    }

    @Override
    public long countByStatus(OutboxEventStatus status) {
        return outboxEventJpaRepository.countByStatus(status);
    }
}
