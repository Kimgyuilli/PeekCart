package com.peekcart.global.kafka;

/**
 * DLQ 소유권 매핑에 등장하는 서비스.
 *
 * <p>ADR-0015 per-service 규약의 {@code <svc>-service} 명명과 맞춘다.
 */
public enum PeekcartService {

    ORDER("order"),
    PRODUCT("product"),
    PAYMENT("payment"),
    NOTIFICATION("notification");

    private final String prefix;

    PeekcartService(String prefix) {
        this.prefix = prefix;
    }

    /** consumer group·DLQ listener group 의 접두사 (예: {@code order} → {@code order-svc-...}). */
    public String prefix() {
        return prefix;
    }

    /** 이 서비스의 DLQ listener group (자기 실패분 소비). */
    public String dlqListenerGroup() {
        return prefix + "-svc-dlq-group";
    }

    /** 이 서비스의 quarantine listener group (group 헤더 부재분 소비). */
    public String quarantineListenerGroup() {
        return prefix + "-svc-dlq-quarantine-group";
    }
}
