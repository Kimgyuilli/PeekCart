package com.peekcart.notification.application.port;

/** 사용자 메시지 전송. 운영 경보용 SlackPort와 별개다. */
@FunctionalInterface
public interface UserNotificationPort {
    void send(String message);
}
