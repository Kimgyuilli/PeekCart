package com.peekcart.global.port;

/**
 * Slack 알림 발송 포트.
 * Outbox, Notification 등 여러 도메인에서 사용하는 횡단 관심사이므로 global에 위치한다.
 */
public interface SlackPort {

    void send(String message);
}
