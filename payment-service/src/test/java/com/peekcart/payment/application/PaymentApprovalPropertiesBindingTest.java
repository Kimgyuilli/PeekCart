package com.peekcart.payment.application;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 승인 정책값 fail-fast 검증 (계획 P12, ADR-0023 · ADR-0007).
 *
 * <p>강제하는 불변식은 <b>하나뿐</b>이다 — {@code unresolved-limit > claim-lease}. 환불과 달리
 * claim lease 와 PG 타임아웃의 관계는 강제하지 않는다: 승인 reconciliation 은 조회만 하고
 * 재호출하지 않으므로(ADR-0023 D5) 조기 회수의 최악이 불필요한 조회 1회이기 때문이다.
 */
@DisplayName("PaymentApprovalProperties 상호관계 fail-fast")
class PaymentApprovalPropertiesBindingTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class, ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Test
    @DisplayName("정상 조합은 바인딩된다 — 중첩 approval 블록까지 값이 닿는다")
    void validCombination() {
        runner.withPropertyValues(base("2m", "24h"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    PaymentApprovalProperties properties = context.getBean(PaymentApprovalProperties.class);
                    assertThat(properties.getApproval().getBatchSize()).isEqualTo(20);
                    assertThat(properties.getApproval().getReconcileIntervalMs()).isEqualTo(60000L);
                });
    }

    @Test
    @DisplayName("unresolved-limit 가 claim-lease 보다 짧으면 부팅 실패 — 회수해 보기 전에 수동 종결 대상이 된다")
    void unresolvedLimitShorterThanLease_failsFast() {
        runner.withPropertyValues(base("10m", "5m"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("같은 값이어도 부팅 실패 — 등호는 '자동 확정을 한 번이라도 시도한다'를 보장하지 못한다")
    void equalValues_failFast() {
        runner.withPropertyValues(base("5m", "5m"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    @DisplayName("approval 블록이 통째로 비면 부팅 실패 — 기본값으로 조용히 도는 것을 막는다")
    void missingApprovalBlock_failsFast() {
        runner.withPropertyValues("app.payment.lease-approval-margin=2m")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String[] base(String claimLease, String unresolvedLimit) {
        return new String[]{
                "app.payment.lease-approval-margin=2m",
                "app.payment.approval.claim-lease=" + claimLease,
                "app.payment.approval.batch-size=20",
                "app.payment.approval.max-batches-per-run=5",
                "app.payment.approval.reconcile-interval-ms=60000",
                "app.payment.approval.lock-at-most-for=10m",
                "app.payment.approval.unresolved-limit=" + unresolvedLimit
        };
    }

    @EnableConfigurationProperties(PaymentApprovalProperties.class)
    static class TestConfig {
    }
}
