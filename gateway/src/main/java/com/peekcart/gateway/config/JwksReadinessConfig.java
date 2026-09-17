package com.peekcart.gateway.config;

import com.peekcart.gateway.auth.JwksKeyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * JWKS readiness 게이팅 + 주기 갱신 (계획 P12 · P18 PR3a 진입 조건).
 *
 * <p><b>cold start 계약</b>: usable key 가 0 이면 트래픽을 받지 않는다. {@link ReadinessState} 로
 * 표현해 {@code /actuator/health/readiness} 에만 반영한다 — {@code HealthIndicator} 로 DOWN 을 내면
 * 루트 {@code /actuator/health} 까지 503 이 되어, 프로세스는 살아 있는데 이미지 기동 검증이 실패한다.
 *
 * <p><b>초기화 순서</b>(GW-2 c1:2): Spring Boot 는 {@link ApplicationReadyEvent} 에서 스스로
 * {@code ACCEPTING_TRAFFIC} 을 게시한다. 따라서 그 <b>이후에</b> 판정해야 한다 — 먼저 게시하면
 * Boot 의 ACCEPTING 이 우리 REFUSING 을 덮어써, 키가 0개인데 최대 한 갱신 주기 동안 ready 로 남는다.
 * 이 리스너는 {@link Ordered#LOWEST_PRECEDENCE} 로 가장 마지막에 실행되며, 최초 JWKS 적재를 한 번
 * 시도한 뒤 실제 키 보유 여부로 readiness 를 확정한다.
 */
@Configuration
public class JwksReadinessConfig {

    private static final Logger log = LoggerFactory.getLogger(JwksReadinessConfig.class);

    @Component
    public static class JwksReadinessGate {

        private final JwksKeyRegistry registry;
        private final ApplicationEventPublisher eventPublisher;

        public JwksReadinessGate(JwksKeyRegistry registry, ApplicationEventPublisher eventPublisher) {
            this.registry = registry;
            this.eventPublisher = eventPublisher;
        }

        /** Boot 의 ACCEPTING_TRAFFIC 게시 이후에 실행돼 실제 키 보유 상태로 확정한다. */
        @EventListener(ApplicationReadyEvent.class)
        @Order(Ordered.LOWEST_PRECEDENCE)
        public void settleReadinessOnStartup() {
            try {
                // 최초 1회는 결과를 기다린다 — readiness 를 확정하려면 적재 시도가 끝나야 한다.
                registry.refreshQuietly().block(Duration.ofSeconds(10));
            } catch (RuntimeException e) {
                log.warn("[alert] 기동 시 JWKS 최초 적재 실패", e);
            }
            publishReadiness();
        }

        /**
         * 주기 갱신 + readiness 재평가. 갱신에 실패해도 last-known-good 을 유지하므로,
         * 한 번이라도 키를 확보했다면 계속 ACCEPTING_TRAFFIC 이다(P12 LKG).
         */
        // [SCHED-LOCK exempt] 인스턴스별 스냅샷 갱신이라 ShedLock 을 걸면 안 된다 — 한 파드만 갱신하고
        // 나머지는 낡은 JWKS 를 서빙하게 되며, 그것이 회전 중 부분 401 장애의 형태다(runbook §1.1).
        @Scheduled(
                initialDelayString = "${app.gateway.jwt.jwks-refresh-interval:PT5M}",
                fixedDelayString = "${app.gateway.jwt.jwks-refresh-interval:PT5M}")
        public void refresh() {
            registry.refreshQuietly()
                    .doFinally(signal -> publishReadiness())
                    .subscribe();
        }

        private void publishReadiness() {
            boolean usable = registry.hasUsableKey();
            if (!usable) {
                log.warn("[alert] usable JWKS key 0개 — readiness=REFUSING_TRAFFIC (트래픽 미수신)");
            }
            AvailabilityChangeEvent.publish(eventPublisher, this,
                    usable ? ReadinessState.ACCEPTING_TRAFFIC : ReadinessState.REFUSING_TRAFFIC);
        }
    }
}
