package com.peekcart.product.infrastructure.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Product sweeper <b>정책값</b> 계약 (ADR-0007).
 *
 * <p>배선은 {@code StockSchedulerWiringIntegrationTest} 가 보고, 여기서는 운영 기본값과 불변식을
 * 고정한다. 배선 테스트가 주기를 짧게 덮으므로 그쪽에서는 운영값을 볼 수 없다.
 */
@DisplayName("[SAGA-SCHEDULER-POLICY-PRODUCT] Product 스케줄러 정책값 계약")
class StockSchedulerPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(StockSchedulerProperties.class)
    static class TestConfig {
    }

    @Test
    @DisplayName("[SAGA-SCHEDULER-POLICY-PRODUCT] base application.yml 이 주기와 lock 을 선언한다")
    void baseYamlDeclaresKeys() throws IOException {
        String yaml = Files.readString(
                Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        assertThat(yaml).contains("lease-sweep-delay: 60s");
        assertThat(yaml).contains("lock-at-most-for: 10m");
        assertThat(yaml).contains("lock-at-least-for: 30s");
    }

    @Test
    @DisplayName("[SAGA-SCHEDULER-POLICY-PRODUCT] @Scheduled·@SchedulerLock placeholder 에 인라인 기본값이 없다")
    void placeholdersHaveNoInlineDefaults() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/com/peekcart/product/infrastructure/scheduler/"
                        + "StockReservationLeaseSweeper.java"),
                StandardCharsets.UTF_8);

        assertThat(Pattern.compile("\\$\\{app\\.scheduler\\.stock\\.[a-z-]+:").matcher(source).find())
                .as("인라인 기본값이 있으면 base 선언이 사라져도 sweeper 가 조용히 돈다")
                .isFalse();

        // 앞 단언이 "placeholder 자체가 없어서" 참이 된 게 아님을 고정한다
        assertThat(source).contains("${app.scheduler.stock.lease-sweep-delay}");
        assertThat(source).contains("${app.scheduler.stock.lock-at-most-for}");
        assertThat(source).contains("${app.scheduler.stock.lock-at-least-for}");
    }

    @Test
    @DisplayName("[SAGA-SCHEDULER-POLICY-PRODUCT] 운영 기본값 조합은 부팅에 성공한다")
    void operationalValuesBind() {
        runner.withPropertyValues(props("60s", "10m", "30s")).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            StockSchedulerProperties p = ctx.getBean(StockSchedulerProperties.class);
            assertThat(p.getLeaseSweepDelay()).isEqualTo(Duration.ofSeconds(60));
            assertThat(p.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(10));
            assertThat(p.getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(30));
        });
    }

    @Test
    @DisplayName("[SAGA-SCHEDULER-POLICY-PRODUCT] lock 창이 뒤집히면 부팅을 막는다")
    void invertedLockWindowFailsFast() {
        runner.withPropertyValues(props("60s", "30s", "10m"))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("[SAGA-SCHEDULER-POLICY-PRODUCT] 주기 0 은 부팅을 막는다")
    void zeroDelayFailsFast() {
        runner.withPropertyValues(props("0s", "10m", "30s"))
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("[SAGA-SCHEDULER-POLICY-PRODUCT] 값 누락은 부팅을 막는다")
    void missingValueFailsFast() {
        runner.withPropertyValues(
                        "app.scheduler.stock.lock-at-most-for=10m",
                        "app.scheduler.stock.lock-at-least-for=30s")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    private static String[] props(String delay, String atMost, String atLeast) {
        return new String[]{
                "app.scheduler.stock.lease-sweep-delay=" + delay,
                "app.scheduler.stock.lock-at-most-for=" + atMost,
                "app.scheduler.stock.lock-at-least-for=" + atLeast,
        };
    }
}
