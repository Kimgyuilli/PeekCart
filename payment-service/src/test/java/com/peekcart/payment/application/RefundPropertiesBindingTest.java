package com.peekcart.payment.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 환불 정책값 fail-fast 검증 (ADR-0007).
 *
 * <p>claim lease 가 PG 호출 최악 소요보다 짧으면 살아있는 claim 을 다른 인스턴스가 회수해
 * <b>같은 결제에 취소를 두 번 시도</b>한다. 이런 조합은 런타임이 아니라 부팅에서 막아야 한다.
 */
@DisplayName("RefundProperties 상호관계 fail-fast")
class RefundPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("정상 조합은 바인딩된다")
    void validCombination() {
        runner.withPropertyValues(
                        "app.refund.claim-lease=5m",
                        "app.refund.pg-timeout=10s",
                        "app.refund.max-attempts=3",
                        "app.refund.retry-backoff=2s",
                        "app.refund.batch-size=5",
                        "app.refund.max-batches-per-run=2",
                        "app.refund.dispatch-interval-ms=30000",
                        "app.refund.reconcile-interval-ms=300000",
                        "app.refund.lock-at-most-for=20m",
                        "app.refund.unresolved-limit=24h")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    @DisplayName("claim-lease 가 PG 호출 최악 소요보다 짧으면 부팅 실패")
    void claimLeaseTooShort_failsFast() {
        runner.withPropertyValues(
                        "app.refund.claim-lease=10s",     // 최악 = 10s×3 + 백오프 6s = 36s
                        "app.refund.pg-timeout=10s",
                        "app.refund.max-attempts=3",
                        "app.refund.retry-backoff=2s",
                        "app.refund.batch-size=5",
                        "app.refund.max-batches-per-run=2",
                        "app.refund.dispatch-interval-ms=30000",
                        "app.refund.reconcile-interval-ms=300000",
                        "app.refund.lock-at-most-for=20m",
                        "app.refund.unresolved-limit=24h")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("lock-at-most-for 가 배치 최악 실행 시간보다 짧으면 부팅 실패")
    void lockTooShort_failsFast() {
        runner.withPropertyValues(
                        "app.refund.claim-lease=5m",
                        "app.refund.pg-timeout=10s",
                        "app.refund.max-attempts=3",
                        "app.refund.retry-backoff=2s",
                        "app.refund.batch-size=5",
                        "app.refund.max-batches-per-run=2",
                        "app.refund.dispatch-interval-ms=30000",
                        "app.refund.reconcile-interval-ms=300000",
                        "app.refund.lock-at-most-for=1m",   // 최악 = 66s × 5 × 2 = 11m
                        "app.refund.unresolved-limit=24h")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("unresolved-limit 가 claim-lease 보다 짧으면 부팅 실패")
    void unresolvedLimitTooShort_failsFast() {
        runner.withPropertyValues(
                        "app.refund.claim-lease=5m",
                        "app.refund.pg-timeout=10s",
                        "app.refund.max-attempts=3",
                        "app.refund.retry-backoff=2s",
                        "app.refund.batch-size=5",
                        "app.refund.max-batches-per-run=2",
                        "app.refund.dispatch-interval-ms=30000",
                        "app.refund.reconcile-interval-ms=300000",
                        "app.refund.lock-at-most-for=20m",
                        "app.refund.unresolved-limit=1m")
                .run(context -> assertThat(context).hasFailed());
    }

    @EnableConfigurationProperties(RefundProperties.class)
    static class TestConfig {
    }
}
