package com.peekcart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Notification 서비스 진입점 (ADR-0011 멀티모듈 · PR2a-2b 첫 peel).
 * <p>base 패키지를 {@code com.peekcart} 로 두어 컴포넌트 스캔/엔티티 스캔/JPA 리포지토리 스캔이
 * 공유 {@code com.peekcart.global.*}(common·common-auth·observability·idempotency 복제)와
 * {@code com.peekcart.notification.*}(도메인)을 모두 포함하도록 한다.
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }

}
