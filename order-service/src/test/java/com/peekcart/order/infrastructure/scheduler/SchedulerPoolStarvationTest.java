package com.peekcart.order.infrastructure.scheduler;

import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 스케줄러 풀이 <b>장시간 잡 하나에 점유되어 다른 잡을 굶기지 않는지</b> 검증한다 (D-024).
 *
 * <p><b>왜 이 테스트인가</b>: D-002 측정 세션에서 {@code orderReservationTimeoutJob} 이 185초
 * 실행되는 동안 {@code orderOutboxPollingJob} 이 <b>완전히 멈췄다</b>(PUBLISHED 고정, 해제 13초 후 재개).
 * 원인은 Spring 기본 {@code ThreadPoolTaskScheduler} 의 pool-size=1 이었고, order-service 에는
 * {@code @Scheduled} 잡이 11개 있다. 백로그가 쌓이면 잡이 길어지고 → 발행이 멈추고 → 백로그가
 * 더 쌓이는 <b>양성 피드백</b>이 성립한다.
 *
 * <p>이 테스트는 스케줄러 <b>빈 자체</b>에 블로킹 작업을 넣어 그 구조를 직접 때린다. 실제
 * {@code @Scheduled} 잡의 타이밍에 의존하면 느리고 불안정하다 — 검증 대상은 "풀이 하나에 막히는가" 다.
 *
 * <p><b>수정 전에는 실패한다</b>: pool-size=1 이면 두 번째 작업이 첫 번째의 완료를 기다리므로
 * 제한 시간 안에 시작하지 못한다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("스케줄러 풀 기아 (D-024)")
class SchedulerPoolStarvationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    /** 블로킹 잡이 점유하는 시간. 아래 대기 한도보다 충분히 길어야 "굶었다" 를 구분할 수 있다. */
    private static final Duration BLOCKING_JOB = Duration.ofSeconds(5);
    /** 두 번째 잡이 시작되기를 기다리는 한도. 블로킹 잡 시간보다 짧아야 의미가 있다. */
    private static final Duration SECOND_JOB_DEADLINE = Duration.ofSeconds(2);

    @Autowired
    TaskScheduler taskScheduler;

    @Test
    @DisplayName("장시간 잡이 도는 동안에도 다른 잡이 실행된다 — 풀이 1이면 실패한다")
    void longRunningJob_doesNotStarveOthers() throws InterruptedException {
        CountDownLatch blockingStarted = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);
        CountDownLatch releaseBlocking = new CountDownLatch(1);

        // (1) 스케줄러 스레드 하나를 길게 점유한다 — 실제 orderReservationTimeoutJob 이 한 일.
        taskScheduler.schedule(() -> {
            blockingStarted.countDown();
            try {
                releaseBlocking.await(BLOCKING_JOB.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, Instant.now());

        assertThat(blockingStarted.await(5, TimeUnit.SECONDS))
                .as("블로킹 잡이 시작되지 않았다 — 테스트 전제 실패")
                .isTrue();

        // (2) 그 사이에 들어온 두 번째 잡 — outbox 발행에 해당한다.
        taskScheduler.schedule(secondRan::countDown, Instant.now());

        boolean ranWhileBlocked = secondRan.await(SECOND_JOB_DEADLINE.toMillis(), TimeUnit.MILLISECONDS);
        releaseBlocking.countDown();

        assertThat(ranWhileBlocked)
                .as("장시간 잡이 도는 동안 다른 잡이 실행되지 못했다 — 스케줄러 풀이 1이라 굶는다(D-024). "
                        + "spring.task.scheduling.pool.size 를 확인하라")
                .isTrue();
    }
}
