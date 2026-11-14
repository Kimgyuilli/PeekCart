package com.peekcart.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxPollingService;
import com.peekcart.order.application.CartCommandService;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.application.dto.AddCartItemCommand;
import com.peekcart.order.application.dto.CreateOrderCommand;
import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-110 opt-in local analysis: does order Hikari pool queueing starve the outbox poller under API load?
 *
 * <p>Runs the real cart-add + order-create path (the two requests per order of the GKE load script) on K closed-loop
 * threads while the real {@link OutboxPollingService} polls on its own thread. One Gradle run per pool size; the size
 * comes from {@code SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE} and is asserted against {@code PEEKCART_POOL_PROBE_SIZE}.
 * Counting uses a JDBC connection outside the pool so measurement does not queue behind the load.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "app.scheduling.enabled=false",
        "app.kafka.listener.enabled=false",
        // 64 스레드의 SQL 로그가 System.out 에서 직렬화되면 커넥션 점유 시간이 부풀려진다.
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.format_sql=false"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@EnabledIfEnvironmentVariable(named = "PEEKCART_POOL_PROBE", matches = "true")
@DisplayName("D-110 로컬 order 풀 경합과 outbox 발행 (명시 실행 전용)")
class OrderPoolContentionLocalProbeTest extends AbstractIntegrationTest {
    private static final int REPETITIONS = 3;
    private static final int LOAD_THREADS = 64;
    private static final int PRODUCTS = 100;
    private static final long SOLO_MS = 15_000;
    private static final long LOAD_MS = 60_000;
    private static final long CONTROL_MS = 10_000;
    private static final long POLL_IDLE_MS = 500;
    private static final CreateOrderCommand ORDER = new CreateOrderCommand("probe", "010-0000-0000", "00000", "probe");

    @Autowired CartCommandService carts;
    @Autowired OrderCommandService orders;
    @Autowired ProductPriceCacheRepository prices;
    @Autowired OutboxPollingService poller;
    @Autowired DataSource dataSource;
    @Autowired MeterRegistry registry;
    @Autowired TransactionTemplate transactions;
    @Autowired ObjectMapper mapper;

    private final AtomicBoolean pollerEnabled = new AtomicBoolean(false);
    private final AtomicBoolean pollerInCycle = new AtomicBoolean(false);
    private final ConcurrentLinkedQueue<Throwable> errors = new ConcurrentLinkedQueue<>();
    private final AtomicLong userSeq = new AtomicLong(1_000_000);
    private final AtomicLong collisions = new AtomicLong();

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES)
    void measurePoolContention() throws Exception {
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        int expectedPool = Integer.parseInt(System.getenv("PEEKCART_POOL_PROBE_SIZE"));
        // 환경변수 이름이 틀리면 기본값 10 이 잡혀 다른 풀 크기 결과로 둔갑한다. 여기서 막는다.
        assertThat(hikari.getMaximumPoolSize()).as("pool size from SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE")
                .isEqualTo(expectedPool);
        Timer usage = onlyTimer("hikaricp.connections.usage");
        Timer acquire = onlyTimer("hikaricp.connections.acquire");
        Timer published = registry.get("outbox.publish").tag("result", "success").timer();

        resetData();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("kind", "environment");
        environment.put("pool", expectedPool);
        environment.put("loadThreads", LOAD_THREADS);
        environment.put("repetitions", REPETITIONS);
        environment.put("soloMs", SOLO_MS);
        environment.put("loadMs", LOAD_MS);
        environment.put("pollIdleMs", POLL_IDLE_MS);
        environment.put("connectionTimeoutMs", hikari.getConnectionTimeout());
        environment.put("cpus", Runtime.getRuntime().availableProcessors());
        environment.put("mysqlImage", SharedContainers.MYSQL.getDockerImageName());
        environment.put("kafkaImage", "apache/kafka:3.8.1");
        emit(environment);

        Thread pollerThread = start("probe-poller", this::pollLoop);
        try {
            // Warm JIT, pool and broker outside the measurement window.
            pollerEnabled.set(true);
            load(8, 10_000, hikari);
            drain();
            verifyIntegrityAndReset("warmup");
            for (int repetition = 1; repetition <= REPETITIONS; repetition++) {
                disablePoller();
                Map<String, Object> solo = phase("solo", repetition, 1, SOLO_MS, hikari, usage, acquire, published);
                assertThat(solo.get("publishedDb")).isEqualTo(0L);
                pollerEnabled.set(true);
                drain();

                phase("load", repetition, LOAD_THREADS, LOAD_MS, hikari, usage, acquire, published);
                drainPhase(repetition, published);
                verifyIntegrityAndReset("repetition-" + repetition);
            }
            // Negative control: with the poller off, nothing may be counted as published.
            disablePoller();
            Map<String, Object> control = phase("load-no-poller", 0, LOAD_THREADS, CONTROL_MS, hikari, usage, acquire, published);
            assertThat(control.get("publishedDb")).isEqualTo(0L);
            assertThat(control.get("publishedTimer")).isEqualTo(0L);
            pollerEnabled.set(true);
            drain();
            verifyIntegrityAndReset("control");
        } finally {
            pollerThread.interrupt();
            pollerThread.join(TimeUnit.MINUTES.toMillis(5));
        }

        assertThat(errors).as("load/poller errors").isEmpty();
    }

    /**
     * 주문번호는 날짜 + UUID 앞 8자리(32비트)라 같은 날 누적 주문이 수만 건이면 생일 충돌이 난다.
     * 반복마다 정합성을 확인하고 비워 누적을 끊는다.
     */
    private void verifyIntegrityAndReset(String scope) throws Exception {
        assertThat(errors).as("load/poller errors").isEmpty();
        long ordersTotal = count("SELECT COUNT(*) FROM orders");
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE event_type = 'order.created' AND status = 'PUBLISHED'"))
                .isEqualTo(ordersTotal);
        assertThat(count("SELECT COUNT(*) FROM outbox_events WHERE status <> 'PUBLISHED'")).isZero();
        emit(Map.of("kind", "integrity", "scope", scope, "orders", ordersTotal, "pendingOrFailed", 0));
        resetData();
    }

    private void resetData() {
        cleanDatabase();
        cleanKafkaTopics(SharedContainers.KAFKA.getBootstrapServers(), "order.created");
        for (long id = 1; id <= PRODUCTS; id++) {
            long productId = id;
            transactions.executeWithoutResult(s -> prices.applyUpdate(productId, 1_000, 1, "probe-" + productId, "ACTIVE"));
        }
    }

    private Map<String, Object> phase(String kind, int repetition, int threads, long millis, HikariDataSource hikari,
                                      Timer usage, Timer acquire, Timer published) throws Exception {
        TimerSnapshot usage0 = TimerSnapshot.of(usage);
        TimerSnapshot acquire0 = TimerSnapshot.of(acquire);
        long published0 = publishedCount();
        long timer0 = published.count();
        long start = System.nanoTime();
        collisions.set(0);
        PendingSampler sampler = new PendingSampler(hikari);
        long iterations;
        try {
            iterations = load(threads, millis, hikari);
        } finally {
            sampler.stop();
        }
        double seconds = (System.nanoTime() - start) / 1e9;
        long publishedDb = publishedCount() - published0;
        long publishedTimer = published.count() - timer0;
        // 단일 poller 스레드는 타이머를 save 직전에 올린다. 경계마다 차이가 최대 1이라 구간 차분은 ±1 안이다.
        assertThat(Math.abs(publishedTimer - publishedDb)).as("timer vs DB published").isLessThanOrEqualTo(1);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("repetition", repetition);
        row.put("threads", threads);
        row.put("seconds", seconds);
        row.put("iterations", iterations);
        row.put("iterationsPerSec", iterations / seconds);
        row.put("orderNumberCollisions", collisions.get());
        row.put("usageCount", usage0.countDelta(usage));
        row.put("usageMeanMs", usage0.meanMs(usage));
        row.put("acquireCount", acquire0.countDelta(acquire));
        row.put("acquireMeanMs", acquire0.meanMs(acquire));
        row.put("pendingAvg", sampler.average());
        row.put("pendingMax", sampler.max());
        row.put("publishedDb", publishedDb);
        row.put("publishedTimer", publishedTimer);
        row.put("publishedPerSec", publishedDb / seconds);
        row.put("backlogAtEnd", count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'"));
        assertThat(usage0.countDelta(usage)).as("hikari usage samples").isPositive();
        assertThat(acquire0.countDelta(acquire)).as("hikari acquire samples").isPositive();
        emit(row);
        return row;
    }

    private void drainPhase(int repetition, Timer published) throws Exception {
        long backlog = count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'");
        long published0 = publishedCount();
        long start = System.nanoTime();
        drain();
        double seconds = (System.nanoTime() - start) / 1e9;
        long publishedDb = publishedCount() - published0;
        emit(Map.of("kind", "drain", "repetition", repetition, "backlogAtStart", backlog, "publishedDb", publishedDb,
                "seconds", seconds, "publishedPerSec", publishedDb / seconds));
    }

    private long load(int threads, long millis, HikariDataSource hikari) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        AtomicLong iterations = new AtomicLong();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long userId = userSeq.incrementAndGet();
            long productId = 1 + i % PRODUCTS;
            workers.add(start("probe-load-" + i, () -> {
                while (System.nanoTime() < deadline) {
                    try {
                        carts.addItem(userId, new AddCartItemCommand(productId, 1));
                        orders.createOrder(userId, ORDER);
                        iterations.incrementAndGet();
                    } catch (DataIntegrityViolationException e) {
                        // 주문번호 충돌은 운영 코드의 관찰 대상이라 세고 계속한다. 그 밖의 무결성 위반은 실패다.
                        if (!String.valueOf(e.getMessage()).contains("uk_orders_order_number")) {
                            errors.add(e);
                            return;
                        }
                        collisions.incrementAndGet();
                    } catch (Throwable e) {
                        errors.add(e);
                        return;
                    }
                }
            }));
        }
        for (Thread worker : workers) worker.join();
        assertThat(errors).as("load errors").isEmpty();
        return iterations.get();
    }

    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (!pollerEnabled.get()) {
                    Thread.sleep(50);
                    continue;
                }
                pollerInCycle.set(true);
                long before = publishedCount();
                poller.pollAndPublish();
                pollerInCycle.set(false);
                // 운영 fixedDelay 500ms 와 같게, 발행할 것이 없던 사이클 뒤에만 쉰다.
                if (publishedCount() == before) Thread.sleep(POLL_IDLE_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable e) {
                errors.add(e);
                pollerInCycle.set(false);
            }
        }
        pollerInCycle.set(false);
    }

    private void drain() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10);
        while (count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'") > 0) {
            assertThat(errors).as("poller errors").isEmpty();
            assertThat(System.nanoTime()).as("drain within 10 minutes").isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    private void disablePoller() throws InterruptedException {
        pollerEnabled.set(false);
        while (pollerInCycle.get()) Thread.sleep(20);
    }

    private long publishedCount() throws Exception {
        return count("SELECT COUNT(*) FROM outbox_events WHERE status = 'PUBLISHED'");
    }

    /** 풀 밖의 별도 연결로 센다. 측정이 부하 뒤에 줄 서면 구간 경계가 밀린다. */
    private long count(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(SharedContainers.MYSQL.getJdbcUrl(),
                SharedContainers.MYSQL.getUsername(), SharedContainers.MYSQL.getPassword());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Thread start(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.start();
        return thread;
    }

    private Timer onlyTimer(String name) {
        Collection<Timer> timers = registry.find(name).timers();
        assertThat(timers).as(name + " registered for the single pool").hasSize(1);
        return timers.iterator().next();
    }

    private void emit(Map<String, ?> row) throws Exception {
        System.out.println("D110_RESULT " + mapper.writeValueAsString(row));
    }

    private record TimerSnapshot(long count, double totalMs) {
        static TimerSnapshot of(Timer timer) {
            return new TimerSnapshot(timer.count(), timer.totalTime(TimeUnit.MILLISECONDS));
        }

        long countDelta(Timer timer) {
            return timer.count() - count;
        }

        double meanMs(Timer timer) {
            long delta = countDelta(timer);
            return delta == 0 ? 0 : (timer.totalTime(TimeUnit.MILLISECONDS) - totalMs) / delta;
        }
    }

    /** HikariPoolMXBean 의 대기 스레드 수를 1초마다 표본한다(GKE 지표 hikaricp_connections_pending 과 같은 값). */
    private static final class PendingSampler {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Integer> samples = new ArrayList<>();
        private final Thread thread;

        PendingSampler(HikariDataSource hikari) {
            thread = start("probe-pending", () -> {
                while (running.get()) {
                    synchronized (samples) {
                        samples.add(hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
                    }
                    try {
                        Thread.sleep(1_000);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            });
        }

        void stop() throws InterruptedException {
            running.set(false);
            thread.interrupt();
            thread.join();
        }

        double average() {
            synchronized (samples) {
                return samples.stream().mapToInt(Integer::intValue).average().orElse(0);
            }
        }

        int max() {
            synchronized (samples) {
                return samples.stream().mapToInt(Integer::intValue).max().orElse(0);
            }
        }
    }
}
