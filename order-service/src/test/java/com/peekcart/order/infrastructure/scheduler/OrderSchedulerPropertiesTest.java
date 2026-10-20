package com.peekcart.order.infrastructure.scheduler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Order 스케줄러 <b>정책값</b> 계약 (ADR-0007).
 *
 * <p>{@link OrderSchedulerWiringIntegrationTest} 가 "잡이 실제로 돈다" 를 보는 반면 여기서는
 * <b>어떤 값으로 도는가</b>를 고정한다. 배선 테스트는 결정성을 위해 주기를 짧게 덮으므로
 * 운영 기본값을 볼 수 없다 — 그래서 두 테스트가 나뉜다.
 *
 * <p>YAML 을 직접 읽는 검사가 하나 있다. 스프링 컨텍스트로는 "base 가 무엇을 선언했나" 를 볼 수
 * 없기 때문이다 — 어떤 값이든 해석되고 나면 출처가 지워진다. 특히 <b>기본값을 두지 않았다</b>는
 * 사실은 해석 결과로는 증명할 수 없다.
 */
@DisplayName("[SAGA-SCHEDULER-POLICY-ORDER] Order 스케줄러 정책값 계약")
class OrderSchedulerPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration
    @EnableConfigurationProperties(OrderSchedulerProperties.class)
    static class TestConfig {
    }

    @Nested
    @DisplayName("[SAGA-SCHEDULER-POLICY-ORDER] 운영 기본값")
    class OperationalDefaults {

        @Test
        @DisplayName("[SAGA-SCHEDULER-POLICY-ORDER] base application.yml 이 세 주기와 lock 을 선언한다")
        void baseYamlDeclaresAllKeys() throws IOException {
            String yaml = Files.readString(
                    Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

            assertThat(yaml).contains("cancel-expired-delay: 60s");
            assertThat(yaml).contains("unconfirmed-reservation-delay: 60s");
            assertThat(yaml).contains("lease-expiry-delay: 60s");
            assertThat(yaml).contains("lock-at-most-for: 10m");
            assertThat(yaml).contains("lock-at-least-for: 30s");
        }

        /**
         * {@code @Scheduled} 의 placeholder 에 <b>기본값을 달지 않았다</b>는 계약이다.
         * {@code ${...:60s}} 형태였다면 base 에서 키가 사라져도 조용히 돌고, 그때 주기를 바꾸려는
         * 운영자는 아무 효과 없는 곳을 고치게 된다.
         */
        @Test
        @DisplayName("[SAGA-SCHEDULER-POLICY-ORDER] @Scheduled·@SchedulerLock placeholder 에 인라인 기본값이 없다")
        void placeholdersHaveNoInlineDefaults() throws IOException {
            String source = Files.readString(
                    Path.of("src/main/java/com/peekcart/order/infrastructure/scheduler/OrderTimeoutScheduler.java"),
                    StandardCharsets.UTF_8);

            Matcher m = Pattern.compile("\\$\\{app\\.scheduler\\.order\\.[a-z-]+:").matcher(source);
            assertThat(m.find())
                    .as("app.scheduler.order.* placeholder 에 ':기본값' 이 붙으면 base 선언이 사라져도 "
                            + "조용히 돌고, 주기를 바꾸려는 운영자는 효과 없는 곳을 고치게 된다")
                    .isFalse();

            // 앞 단언이 "placeholder 자체가 없어서" 참이 된 게 아님을 고정한다
            assertThat(source).contains("${app.scheduler.order.cancel-expired-delay}");
            assertThat(source).contains("${app.scheduler.order.lock-at-most-for}");
            assertThat(source).contains("${app.scheduler.order.lock-at-least-for}");
        }
    }

    @Nested
    @DisplayName("[SAGA-SCHEDULER-POLICY-ORDER] lock 불변식")
    class LockInvariants {

        @Test
        @DisplayName("[SAGA-SCHEDULER-POLICY-ORDER] 운영 기본값 조합은 부팅에 성공한다")
        void operationalValuesBind() {
            runner.withPropertyValues(props("60s", "60s", "60s", "10m", "30s"))
                    .run(ctx -> {
                        assertThat(ctx).hasNotFailed();
                        OrderSchedulerProperties p = ctx.getBean(OrderSchedulerProperties.class);
                        assertThat(p.getCancelExpiredDelay()).isEqualTo(Duration.ofSeconds(60));
                        assertThat(p.getUnconfirmedReservationDelay()).isEqualTo(Duration.ofSeconds(60));
                        assertThat(p.getLeaseExpiryDelay()).isEqualTo(Duration.ofSeconds(60));
                        assertThat(p.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(10));
                        assertThat(p.getLockAtLeastFor()).isEqualTo(Duration.ofSeconds(30));
                    });
        }

        @Test
        @DisplayName("[SAGA-SCHEDULER-POLICY-ORDER] lock-at-least-for >= lock-at-most-for 는 부팅을 막는다 — ShedLock 이 lock 을 놓지 못한다")
        void invertedLockWindowFailsFast() {
            runner.withPropertyValues(props("60s", "60s", "60s", "30s", "10m"))
                    .run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("[SAGA-SCHEDULER-POLICY-ORDER] 주기 0 은 부팅을 막는다 — fixedDelay 0 은 잡이 쉬지 않는다")
        void zeroDelayFailsFast() {
            runner.withPropertyValues(props("0s", "60s", "60s", "10m", "30s"))
                    .run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("[SAGA-SCHEDULER-POLICY-ORDER] 값 누락은 부팅을 막는다 — 조용히 기본값으로 도는 경로가 없다")
        void missingValueFailsFast() {
            runner.withPropertyValues(
                            "app.scheduler.order.unconfirmed-reservation-delay=60s",
                            "app.scheduler.order.lease-expiry-delay=60s",
                            "app.scheduler.order.lock-at-most-for=10m",
                            "app.scheduler.order.lock-at-least-for=30s")
                    .run(ctx -> assertThat(ctx).hasFailed());
        }
    }

    private static String[] props(String cancel, String unconfirmed, String lease,
                                  String atMost, String atLeast) {
        return new String[]{
                "app.scheduler.order.cancel-expired-delay=" + cancel,
                "app.scheduler.order.unconfirmed-reservation-delay=" + unconfirmed,
                "app.scheduler.order.lease-expiry-delay=" + lease,
                "app.scheduler.order.lock-at-most-for=" + atMost,
                "app.scheduler.order.lock-at-least-for=" + atLeast,
        };
    }
}
