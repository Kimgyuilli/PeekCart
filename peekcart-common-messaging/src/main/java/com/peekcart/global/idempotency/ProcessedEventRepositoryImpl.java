package com.peekcart.global.idempotency;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * {@link ProcessedEventRepository} 구현체. {@link ProcessedEventJpaRepository}에 위임한다.
 */
@Repository
@RequiredArgsConstructor
public class ProcessedEventRepositoryImpl implements ProcessedEventRepository {

    private final ProcessedEventJpaRepository processedEventJpaRepository;

    @Override
    public boolean exists(String eventId, String consumerGroup) {
        return processedEventJpaRepository.existsByEventIdAndConsumerGroup(eventId, consumerGroup);
    }

    @Override
    public ProcessedEvent save(ProcessedEvent event) {
        return processedEventJpaRepository.save(event);
    }
}
