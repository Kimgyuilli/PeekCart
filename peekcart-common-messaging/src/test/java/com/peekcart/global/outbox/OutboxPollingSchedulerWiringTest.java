package com.peekcart.global.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.port.SlackPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.FixedDelayTask;
import org.springframework.scheduling.config.ScheduledTask;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * 설정한 주기·최소 보존·배치가 <b>실제 스케줄러 등록, ShedLock 이 받는 lock 설정, poller 조회 한도</b>에
 * 들어가는지 본다 (ADR-0045).
 *
 * <p>값을 고정한 문자열 비교로는 이것을 증명하지 못한다. 애노테이션이 placeholder 를 읽어도 그 값이 실행 경로에서
 * 다른 것으로 바뀌면(하드코딩으로 되돌림, 다른 키) 문자열 검사는 통과한다. 그래서 운영 기본값과 다른 값
 * (300ms·200ms·37건)을 주고 <b>실제 발화</b>에서 그 값이 관측되는지 본다. 스케줄러 메서드는 직접 부르지 않는다.
 *
 * <p>DB·Kafka 없이 돈다. LockProvider 는 받은 lock 설정을 기록만 하는 가짜이고, ShedLock 의 AOP 프록시와
 * 설정 추출({@code SpringLockConfigurationExtractor})은 실제 것이다.
 */
@DisplayName("[OUTBOX-POLICY] outbox poller 설정 배선")
class OutboxPollingSchedulerWiringTest {

    // OutboxPollingService 의 @Value Duration("6s")을 Boot 앱과 같은 변환기로 읽게 한다.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withConfiguration(AutoConfigurations.of(
                    PropertyPlaceholderAutoConfiguration.class,
                    ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(WiringConfig.class);

    @Test
    @DisplayName("[OUTBOX-POLICY] 주기·최소 보존·배치·락 이름이 스케줄러·ShedLock·poller 에 그대로 들어간다")
    void configuredValuesReachSchedulerLockAndPoller() {
        runner.withPropertyValues(
                        "app.outbox.polling.delay=300ms",
                        "app.outbox.polling.lock-at-least-for=200ms",
                        "app.outbox.polling.batch-size=37",
                        "app.outbox.lock-name=probeOutboxPollingJob")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();

                    // ① 스케줄러: 등록된 작업은 poller 하나이고, fixedDelay 가 설정한 주기다.
                    Set<ScheduledTask> tasks = ctx.getBean(ScheduledAnnotationBeanPostProcessor.class)
                            .getScheduledTasks();
                    assertThat(tasks).hasSize(1);
                    ScheduledTask task = tasks.iterator().next();
                    assertThat(task.toString()).contains("OutboxPollingScheduler").contains("pollAndPublish");
                    assertThat(task.getTask()).isInstanceOf(FixedDelayTask.class);
                    assertThat(((FixedDelayTask) task.getTask()).getIntervalDuration())
                            .isEqualTo(Duration.ofMillis(300));

                    // ② ShedLock: 실제 발화에서 받은 lock 설정이 설정한 이름·최소 보존과 5분 상한이다.
                    RecordingLockProvider lockProvider = ctx.getBean(RecordingLockProvider.class);
                    await().atMost(5, TimeUnit.SECONDS).until(() -> !lockProvider.configurations.isEmpty());
                    LockConfiguration lock = lockProvider.configurations.get(0);
                    assertThat(lock.getName()).isEqualTo("probeOutboxPollingJob");
                    assertThat(lock.getLockAtLeastFor()).isEqualTo(Duration.ofMillis(200));
                    assertThat(lock.getLockAtMostFor()).isEqualTo(Duration.ofMinutes(5));

                    // ③ poller: lock 안에서 실제 서비스가 설정한 배치 크기로 조회한다.
                    verify(ctx.getBean(OutboxEventRepository.class), timeout(5_000).atLeastOnce())
                            .findPendingEvents(37);
                });
    }

    /**
     * 키가 빠진 채 스케줄러 문맥이 뜨면 ShedLock 은 실행마다 placeholder 를 풀지 못해 예외를 던진다.
     * 그 상태가 부팅 실패로 바뀌었는지 본다. 원인 메시지는 경로에 따라 placeholder 해석 실패이거나
     * properties 검증 실패이고, 어느 쪽이든 빠진 키 이름을 담는다.
     */
    @Test
    @DisplayName("[OUTBOX-POLICY] 주기·최소 보존 키가 없으면 스케줄러 문맥이 부팅에 실패한다")
    void missingKeysFailBeforeFiring() {
        runner.withPropertyValues("app.outbox.polling.batch-size=37")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("app.outbox.polling");
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
    @Import({OutboxPollingScheduler.class, OutboxPollingService.class})
    static class WiringConfig {

        @Bean
        RecordingLockProvider lockProvider() {
            return new RecordingLockProvider();
        }

        @Bean
        OutboxEventRepository outboxEventRepository() {
            // findPendingEvents 는 빈 목록을 돌려준다(Mockito 기본값) — 발행 본문은 이 테스트의 관심이 아니다.
            return mock(OutboxEventRepository.class);
        }

        @Bean
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> kafkaTemplate() {
            return mock(KafkaTemplate.class);
        }

        @Bean
        SlackPort slackPort() {
            return mock(SlackPort.class);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /** ShedLock 이 실행마다 만든 lock 설정을 기록하고 항상 lock 을 준다. */
    static class RecordingLockProvider implements LockProvider {

        final List<LockConfiguration> configurations = new CopyOnWriteArrayList<>();

        @Override
        public Optional<SimpleLock> lock(LockConfiguration lockConfiguration) {
            configurations.add(lockConfiguration);
            return Optional.of(() -> { });
        }
    }
}
