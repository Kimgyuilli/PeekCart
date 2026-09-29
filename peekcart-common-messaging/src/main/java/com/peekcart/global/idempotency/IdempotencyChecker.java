package com.peekcart.global.idempotency;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * Kafka Consumer 멱등성 처리기.
 * {@code processed_events} 테이블을 조회하여 중복 이벤트를 필터링하고,
 * 신규 이벤트만 처리 이력을 선점 기록한 뒤 비즈니스 로직을 실행한다.
 * <p>
 * 호출자의 {@code @Transactional} 컨텍스트에 참여하여
 * 비즈니스 로직 + 처리 이력 기록의 원자성을 보장한다.
 * <p>
 * 동시 중복 소비 시 UK 제약이 선점 락 역할을 하여 비즈니스 로직 이중 실행을 방지한다.
 * {@code action} 실패 시 트랜잭션 전체가 롤백되어 처리 이력도 함께 삭제된다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IdempotencyChecker {

    private final ProcessedEventRepository processedEventRepository;

    /**
     * 해당 이벤트가 미처리 상태이면 처리 이력을 선점 기록하고 비즈니스 로직을 실행한다.
     * <p>
     * Kafka 리밸런스 등으로 동일 메시지가 동시 소비될 경우,
     * {@code processed_events} UK 제약이 하나만 통과시키고 나머지는 중복으로 처리한다.
     *
     * @param eventId       Kafka 메시지의 이벤트 ID
     * @param consumerGroup Kafka Consumer Group ID
     * @param action        실행할 비즈니스 로직
     * @return 실행 여부. 중복 이벤트이면 {@code false}
     */
    public boolean executeIfNew(String eventId, String consumerGroup, Runnable action) {
        if (processedEventRepository.exists(eventId, consumerGroup)) {
            log.debug("중복 이벤트 무시 — eventId={}, consumerGroup={}", eventId, consumerGroup);
            return false;
        }
        try {
            processedEventRepository.save(ProcessedEvent.create(eventId, consumerGroup));
        } catch (DataIntegrityViolationException e) {
            log.debug("동시 중복 이벤트 감지 — eventId={}, consumerGroup={}", eventId, consumerGroup);
            return false;
        }
        action.run();
        return true;
    }
}
