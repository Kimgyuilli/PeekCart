package com.peekcart.global.idempotency;

/**
 * Consumer 멱등성 처리 이력 저장소 인터페이스.
 */
public interface ProcessedEventRepository {

    /**
     * 해당 이벤트가 특정 Consumer Group에서 이미 처리되었는지 확인한다.
     *
     * @param eventId       이벤트 ID
     * @param consumerGroup Consumer Group ID
     * @return 이미 처리된 경우 {@code true}
     */
    boolean exists(String eventId, String consumerGroup);

    /**
     * 처리 이력을 저장한다.
     *
     * @param event 처리 이력 엔티티
     * @return 저장된 엔티티
     */
    ProcessedEvent save(ProcessedEvent event);
}
