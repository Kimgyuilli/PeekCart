package com.peekcart.global.outbox;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outbox poller 주기·최소 보존 <b>부팅 검증</b> 계약 (ADR-0045).
 *
 * <p>ShedLock 은 lock 설정을 실행마다 만들기 때문에, 이 검증이 없으면 잘못된 값은 부팅이 아니라 매 실행의
 * 예외(또는 D-075 처럼 조용한 skip)로만 드러난다. 여기서는 그 값들이 <b>부팅을 막는지</b>를 본다.
 * 값이 실제 스케줄러·ShedLock 에 들어가는지는 {@link OutboxPollingSchedulerWiringTest} 소관이다.
 */
@DisplayName("[OUTBOX-POLICY] outbox poller 주기·최소 보존 부팅 검증")
class OutboxPollingPropertiesTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(TestConfig.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OutboxPollingProperties.class)
    static class TestConfig {
    }

    @Nested
    @DisplayName("[OUTBOX-POLICY] 불변식")
    class Invariants {

        @Test
        @DisplayName("[OUTBOX-POLICY] 3서비스 기본값(500ms·400ms)은 부팅에 성공하고 그대로 바인딩된다")
        void serviceDefaultsBind() {
            runner.withPropertyValues(props("500ms", "400ms")).run(ctx -> {
                assertThat(ctx).hasNotFailed();
                OutboxPollingProperties p = ctx.getBean(OutboxPollingProperties.class);
                assertThat(p.getDelay()).isEqualTo(Duration.ofMillis(500));
                assertThat(p.getLockAtLeastFor()).isEqualTo(Duration.ofMillis(400));
            });
        }

        @Test
        @DisplayName("[OUTBOX-POLICY] 최소 보존 0 은 허용된다 — 하한은 0 이다")
        void zeroLockAtLeastForBinds() {
            runner.withPropertyValues(props("500ms", "0s"))
                    .run(ctx -> assertThat(ctx).hasNotFailed());
        }

        @Test
        @DisplayName("[OUTBOX-POLICY] 최소 보존이 주기보다 길면(D-075: 500ms·4s) 부팅을 막는다")
        void lockLongerThanDelayFailsFast() {
            runner.withPropertyValues(props("500ms", "4s")).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("lock-at-least-for 는 0 이상이고 delay 보다");
            });
        }

        @Test
        @DisplayName("[OUTBOX-POLICY] 최소 보존이 주기와 같으면 부팅을 막는다 — 경계값")
        void lockEqualToDelayFailsFast() {
            runner.withPropertyValues(props("500ms", "500ms"))
                    .run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("[OUTBOX-POLICY] 최소 보존이 lockAtMostFor(5분) 이상이면 주기가 더 길어도 부팅을 막는다")
        void lockNotBelowLockAtMostForFailsFast() {
            runner.withPropertyValues(props("10m", "5m")).run(ctx -> {
                assertThat(ctx).hasFailed();
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("lockAtMostFor(5분)보다");
            });
        }

        @Test
        @DisplayName("[OUTBOX-POLICY] 음수 최소 보존은 부팅을 막는다 — ShedLock 은 이것을 거르지 않는다")
        void negativeLockFailsFast() {
            runner.withPropertyValues(props("500ms", "-1s"))
                    .run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("[OUTBOX-POLICY] 주기 0 은 부팅을 막는다")
        void zeroDelayFailsFast() {
            runner.withPropertyValues(props("0s", "0s"))
                    .run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("[OUTBOX-POLICY] 주기 키가 없으면 부팅을 막는다 — 조용히 기본값으로 도는 경로가 없다")
        void missingDelayFailsFast() {
            runner.withPropertyValues("app.outbox.polling.lock-at-least-for=400ms")
                    .run(ctx -> assertThat(ctx).hasFailed());
        }

        @Test
        @DisplayName("[OUTBOX-POLICY] 최소 보존 키가 없으면 부팅을 막는다")
        void missingLockAtLeastForFailsFast() {
            runner.withPropertyValues("app.outbox.polling.delay=500ms")
                    .run(ctx -> assertThat(ctx).hasFailed());
        }
    }

    @Nested
    @DisplayName("[OUTBOX-POLICY] 애노테이션 placeholder")
    class Placeholders {

        /**
         * 검증하는 키와 스케줄러가 읽는 키가 같고, 스케줄러 쪽에 <b>인라인 기본값이 없다</b>는 계약이다.
         * {@code ${...:5s}} 형태가 되살아나면 base 선언이 사라져도 조용히 돌고(D-002 의 5s 가 그랬다),
         * 처리율을 바꾸려는 사람은 효과 없는 곳을 고치게 된다.
         */
        @Test
        @DisplayName("[OUTBOX-POLICY] @Scheduled·@SchedulerLock 은 검증하는 키를 기본값 없이 읽는다")
        void annotationsReadValidatedKeysWithoutDefaults() throws NoSuchMethodException {
            var method = OutboxPollingScheduler.class.getMethod("pollAndPublish");
            Scheduled scheduled = method.getAnnotation(Scheduled.class);
            SchedulerLock lock = method.getAnnotation(SchedulerLock.class);

            assertThat(scheduled.fixedDelayString()).isEqualTo("${app.outbox.polling.delay}");
            assertThat(lock.lockAtLeastFor()).isEqualTo("${app.outbox.polling.lock-at-least-for}");
        }

        @Test
        @DisplayName("[OUTBOX-POLICY] lockAtMostFor 는 5분이고 properties 가 검사하는 상한과 같은 값이다")
        void lockAtMostForMatchesValidatedBound() throws NoSuchMethodException {
            SchedulerLock lock = OutboxPollingScheduler.class.getMethod("pollAndPublish")
                    .getAnnotation(SchedulerLock.class);

            assertThat(lock.lockAtMostFor()).isEqualTo(OutboxPollingScheduler.LOCK_AT_MOST_FOR);
            assertThat(Duration.parse(OutboxPollingScheduler.LOCK_AT_MOST_FOR)).isEqualTo(Duration.ofMinutes(5));
        }
    }

    private static String[] props(String delay, String lockAtLeastFor) {
        return new String[]{
                "app.outbox.polling.delay=" + delay,
                "app.outbox.polling.lock-at-least-for=" + lockAtLeastFor,
        };
    }
}
