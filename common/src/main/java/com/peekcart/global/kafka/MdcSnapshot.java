package com.peekcart.global.kafka;

import org.slf4j.MDC;

/**
 * MDC 의 traceId / userId 를 한 번에 캡처하는 정적 헬퍼.
 * Outbox publisher 가 호출 스레드 (HTTP 요청 스레드) 의 MDC 를 엔티티 컬럼으로 영속화하기 위해 사용.
 *
 * @see com.peekcart.global.outbox.OutboxEvent#create
 */
public final class MdcSnapshot {

    private MdcSnapshot() {
    }

    public static Snapshot current() {
        return new Snapshot(MDC.get("traceId"), MDC.get("userId"));
    }

    public record Snapshot(String traceId, String userId) {
    }
}
