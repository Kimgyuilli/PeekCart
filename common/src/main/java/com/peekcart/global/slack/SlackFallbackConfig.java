package com.peekcart.global.slack;

import com.peekcart.global.port.SlackPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Slack no-op fallback (ADR-0014 전환기 · PR3b GP-2 loop2/loop3).
 *
 * <p>{@link SlackNotificationClient} 는 {@code @ConditionalOnProperty(slack.webhook.url)} 라
 * webhook 미설정 서비스에서는 빈이 없다. 그런데 product/order/payment 는 {@code SlackPort} 를
 * 생성자 주입(DLQ·에러 알림)하므로 빈이 없으면 부팅에 실패한다. 이들 서비스는 Slack 알림이
 * 비핵심이라 {@code slack.noop-fallback.enabled=true} 로 본 no-op 빈을 켜 부팅을 보장한다.
 *
 * <p><b>notification 은 fallback 을 켜지 않는다</b> — webhook 누락 시 빈 부재로 부팅 실패(fail-fast)
 * 해야 알림이 조용히 유실되지 않는다(loop2 #2). real({@code slack.webhook.url}) 과
 * no-op({@code slack.noop-fallback.enabled}) 은 두 property gate 로 상호배타이며,
 * {@code @ConditionalOnMissingBean} 으로 실 빈(또는 테스트 제공 빈)이 있으면 등록되지 않는다.
 */
@Slf4j
@Configuration
public class SlackFallbackConfig {

    @Bean
    @ConditionalOnMissingBean(SlackPort.class)
    @ConditionalOnProperty(name = "slack.noop-fallback.enabled", havingValue = "true")
    public SlackPort noopSlackPort() {
        log.info("Slack no-op fallback 활성화 — 알림은 발송되지 않고 무시됩니다 (slack.noop-fallback.enabled=true)");
        return message -> log.debug("Slack no-op: {}", message);
    }
}
