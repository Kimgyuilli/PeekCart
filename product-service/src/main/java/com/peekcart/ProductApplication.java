package com.peekcart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Product 서비스 진입점 (ADR-0011 멀티모듈 · Product peel — 첫 발행 서비스).
 * <p>base 패키지를 {@code com.peekcart} 로 두어 컴포넌트/엔티티/JPA 리포지토리 스캔이
 * 공유 {@code com.peekcart.global.*}(common·common-auth·observability + 복제한 outbox/idempotency/ShedLock)와
 * {@code com.peekcart.product.*}(도메인)을 모두 포함하도록 한다.
 * <p>{@link EnableScheduling} 은 자체 outbox poller({@code OutboxPollingScheduler})의 {@code @Scheduled} 구동용.
 */
@EnableScheduling
@SpringBootApplication
public class ProductApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProductApplication.class, args);
    }

}
