package com.peekcart.global.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * retention floor fail-fast — 부팅 시 컨텍스트 검증 (ADR-0012 D5).
 * retention 이 floor max 미만이면 {@code @Validated @ConfigurationProperties} 바인딩이 실패해
 * ApplicationContext 기동이 실패함을 검증한다(Testcontainers 없이 경량).
 */
@DisplayName("retention floor fail-fast — 부팅 컨텍스트 검증")
class RetentionFailFastTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    @Configuration
    @EnableConfigurationProperties(IdempotencyRetentionProperties.class)
    static class Config {
    }

    @Test
    @DisplayName("retention 이 floor 와 안전여유를 모두 넘으면 컨텍스트 기동 성공")
    void validBoots() {
        runner.withPropertyValues(
                        "app.idempotency.retention=9d",
                        "app.idempotency.clock-skew-budget=5m",
                        "app.idempotency.cleanup-safety-budget=1d",
                        "app.idempotency.floor.kafka-topic-retention=7d",
                        "app.idempotency.floor.max-consumer-downtime=24h",
                        "app.idempotency.floor.dlq-replay-window=7d",
                        "app.idempotency.floor.backfill-replay-window=7d")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("도입 이전 값(retention 7d == dlq-replay-window 7d)은 안전여유 부족으로 기동 실패 (ADR-0020 D4-3)")
    void noSafetyMarginFailsBoot() {
        runner.withPropertyValues(
                        "app.idempotency.retention=7d",
                        "app.idempotency.clock-skew-budget=5m",
                        "app.idempotency.cleanup-safety-budget=1d",
                        "app.idempotency.floor.kafka-topic-retention=7d",
                        "app.idempotency.floor.max-consumer-downtime=24h",
                        "app.idempotency.floor.dlq-replay-window=7d",
                        "app.idempotency.floor.backfill-replay-window=7d")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("budget 키 누락은 @NotNull 로 기동 실패")
    void missingBudgetFailsBoot() {
        runner.withPropertyValues(
                        "app.idempotency.retention=9d",
                        "app.idempotency.floor.kafka-topic-retention=7d",
                        "app.idempotency.floor.max-consumer-downtime=24h",
                        "app.idempotency.floor.dlq-replay-window=7d",
                        "app.idempotency.floor.backfill-replay-window=7d")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("retention < floor 이면 컨텍스트 기동 실패 (fail-fast)")
    void belowFloorFailsBoot() {
        runner.withPropertyValues(
                        "app.idempotency.retention=1d",
                        "app.idempotency.clock-skew-budget=5m",
                        "app.idempotency.cleanup-safety-budget=1d",
                        "app.idempotency.floor.kafka-topic-retention=7d",
                        "app.idempotency.floor.max-consumer-downtime=24h",
                        "app.idempotency.floor.dlq-replay-window=7d",
                        "app.idempotency.floor.backfill-replay-window=7d")
                .run(ctx -> assertThat(ctx).hasFailed());
    }
}
