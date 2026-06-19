package com.peekcart.global.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link ProcessedEvent} JPA Repository.
 */
public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventIdAndConsumerGroup(String eventId, String consumerGroup);
}
