package com.peekcart.global.slack;

import com.peekcart.global.port.SlackPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slack real({@link SlackNotificationClient}) ↔ no-op({@link SlackFallbackConfig}) 게이팅 상호배타 검증
 * (PR3b GP-2 loop2 #2 / loop3 #1).
 *
 * <p>핵심: presence-based {@code @ConditionalOnProperty(slack.webhook.url)} 와
 * {@code @ConditionalOnProperty(slack.noop-fallback.enabled)} 두 gate 로
 * 두 빈이 동시에 뜨지 않으며, 둘 다 없으면 SlackPort 빈이 부재(= notification fail-fast)함을 보장.
 */
class SlackPortConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SlackNotificationClient.class, SlackFallbackConfig.class);

    @Test
    @DisplayName("webhook 설정 → 실 SlackNotificationClient 만 등록 (notification 경로)")
    void realClientWhenWebhookSet() {
        runner.withPropertyValues("slack.webhook.url=https://hooks.slack.com/services/real")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SlackPort.class);
                    assertThat(ctx.getBean(SlackPort.class)).isInstanceOf(SlackNotificationClient.class);
                });
    }

    @Test
    @DisplayName("webhook 없이 noop-fallback=true → no-op SlackPort 만 등록 (product/order/payment 경로)")
    void noopWhenFallbackEnabledAndNoWebhook() {
        runner.withPropertyValues("slack.noop-fallback.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SlackPort.class);
                    assertThat(ctx.getBean(SlackPort.class)).isNotInstanceOf(SlackNotificationClient.class);
                });
    }

    @Test
    @DisplayName("webhook 도 fallback 도 없음 → SlackPort 빈 부재 (생성자 주입 서비스는 fail-fast)")
    void noBeanWhenNeitherConfigured() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(SlackPort.class));
    }

    @Test
    @DisplayName("webhook + noop 둘 다 설정돼도 실 빈 우선, no-op 미등록 (상호배타)")
    void realWinsWhenBothConfigured() {
        runner.withPropertyValues(
                        "slack.webhook.url=https://hooks.slack.com/services/real",
                        "slack.noop-fallback.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SlackPort.class);
                    assertThat(ctx.getBean(SlackPort.class)).isInstanceOf(SlackNotificationClient.class);
                });
    }
}
