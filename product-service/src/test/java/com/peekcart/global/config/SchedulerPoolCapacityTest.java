package com.peekcart.global.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** D-078: finite Boot scheduler capacity, not real job throughput or production sizing. */
class SchedulerPoolCapacityTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class, ProbeConfiguration.class)
            .withPropertyValues("spring.config.location=classpath:/application.yml",
                    "spring.profiles.active=test", "app.scheduling.enabled=true");

    @Test
    void configuredPoolFourProgressesWithThreeOccupiedSlots() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            exercise(context, 4, 3, false);
        });
    }

    @Test
    void configuredPoolFourStallsAtFourOccupiedSlotsAndRecoversOnRelease() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            exercise(context, 4, 4, true);
        });
    }

    @Test
    void singleThreadCounterfactualStallsAtOneOccupiedSlotAndRecoversOnRelease() {
        runner.withPropertyValues("spring.task.scheduling.pool.size=1").run(context -> {
            assertThat(context).hasNotFailed();
            exercise(context, 1, 1, true);
        });
    }

    private void exercise(ApplicationContext context, int capacity, int blockers, boolean expectStall)
            throws Exception {
        assertThat(context.getEnvironment().getProperty("spring.application.name")).isEqualTo("product-service");
        assertThat(context.getEnvironment().getProperty("spring.task.scheduling.pool.size", Integer.class))
                .isEqualTo(capacity);
        assertThat(context.getBeanNamesForType(TaskScheduler.class)).containsExactly("taskScheduler");
        ThreadPoolTaskScheduler scheduler = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
        assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(capacity);
        TickProbe probe = context.getBean(TickProbe.class);
        var tasks = context.getBean(ScheduledAnnotationBeanPostProcessor.class).getScheduledTasks();
        assertThat(tasks).hasSize(1);
        var registered = tasks.iterator().next().getTask().getRunnable();
        assertThat(registered).isInstanceOf(ScheduledMethodRunnable.class);
        assertThat(((ScheduledMethodRunnable) registered).getTarget()).isSameAs(probe);
        assertThat(((ScheduledMethodRunnable) registered).getMethod().getName()).isEqualTo("tick");
        await().atMost(5, TimeUnit.SECONDS).until(() -> probe.ticks.get() > 0);

        CountDownLatch started = new CountDownLatch(blockers);
        CountDownLatch release = new CountDownLatch(1);
        List<ScheduledFuture<?>> held = new ArrayList<>();
        try {
            for (int i = 0; i < blockers; i++) {
                held.add(scheduler.schedule(() -> {
                    started.countDown();
                    try {
                        assertThat(release.await(30, TimeUnit.SECONDS)).as("blocker watchdog").isTrue();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("blocker interrupted before release", interrupted);
                    }
                }, Instant.now()));
            }
            assertThat(started.await(5, TimeUnit.SECONDS)).as("all blockers entered").isTrue();
            assertThat(held).allSatisfy(future -> assertThat(future.isDone()).isFalse());
            assertThat(scheduler.getScheduledThreadPoolExecutor().getActiveCount()).isGreaterThanOrEqualTo(blockers);
            int before = probe.ticks.get();
            CountDownLatch due = new CountDownLatch(1);
            AtomicLong entered = new AtomicLong();
            long queued = System.nanoTime();
            ScheduledFuture<?> witness = scheduler.schedule(() -> {
                entered.set(System.nanoTime());
                due.countDown();
            }, Instant.now());
            if (expectStall) {
                assertThat(due.await(150, TimeUnit.MILLISECONDS)).as("due work while every slot is held").isFalse();
                assertThat(probe.ticks.get()).as("registered probe while every slot is held").isEqualTo(before);
            } else {
                assertThat(due.await(5, TimeUnit.SECONDS)).as("due work on remaining slot").isTrue();
                await().atMost(5, TimeUnit.SECONDS).until(() -> probe.ticks.get() > before);
            }
            assertThat(release.getCount()).isEqualTo(1);
            assertThat(held).allSatisfy(future -> assertThat(future.isDone()).isFalse());
            release.countDown();
            witness.get(5, TimeUnit.SECONDS);
            await().atMost(5, TimeUnit.SECONDS).until(() -> probe.ticks.get() > before);
            System.out.printf("D078_SCHEDULER pool=%d blockers=%d registered=1 stalled=%s ticksBefore=%d ticksAfter=%d queueDelayNs=%d%n",
                    capacity, blockers, expectStall, before, probe.ticks.get(), entered.get() - queued);
        } finally {
            release.countDown();
            for (ScheduledFuture<?> future : held) {
                future.get(5, TimeUnit.SECONDS);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        TickProbe tickProbe() {
            return new TickProbe();
        }
    }

    static class TickProbe {
        private final AtomicInteger ticks = new AtomicInteger();

        @Scheduled(fixedDelay = 20)
        public void tick() {
            ticks.incrementAndGet();
        }
    }
}
