package com.peekcart;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Order 서비스 진입점 (ADR-0011 멀티모듈 · Order peel PR-a — publisher+consumer 양방향 서비스).
 * <p>base 패키지를 {@code com.peekcart} 로 두어 컴포넌트/엔티티/JPA 리포지토리 스캔이
 * 공유 {@code com.peekcart.global.*}(common·common-auth·observability + 복제한 outbox/idempotency/ShedLock)와
 * {@code com.peekcart.order.*}(도메인)을 모두 포함하도록 한다.
 * <p>{@code @Scheduled} 구동(자체 outbox poller {@code OutboxPollingScheduler} ·
 * {@code OrderTimeoutScheduler} 의 {@code orderTimeoutCancelJob}·{@code orderReservationTimeoutJob})은
 * 공유 {@code com.peekcart.global.config.SchedulingConfig} 가 담당한다 — 프로퍼티로 게이트해
 * 테스트에서 배경 잡이 돌지 않게 하기 위해서다(D-032).
 */
@SpringBootApplication
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }

}
