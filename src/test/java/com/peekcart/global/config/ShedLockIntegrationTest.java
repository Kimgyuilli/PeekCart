package com.peekcart.global.config;

import com.peekcart.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.task.scheduling.pool.size=1",
        "toss.payments.secret-key=test_sk_fake"
})
@DisplayName("ShedLock 통합 테스트")
class ShedLockIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("shedlock 테이블이 Flyway로 생성되고, 스케줄러 실행 시 락 레코드가 기록된다")
    void shedlockTableExistsAndLockRecordCreated() {
        // shedlock 테이블 존재 확인
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'shedlock'",
                Integer.class);
        assertThat(tableCount).isEqualTo(1);

        // 스케줄러가 실행되어 shedlock 테이블에 락 레코드가 생성될 때까지 대기
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Integer lockCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM shedlock", Integer.class);
                    assertThat(lockCount).isGreaterThanOrEqualTo(1);
                });
    }

    @Test
    @DisplayName("rootOutboxPollingJob 락 레코드가 생성된다 (Product peel: 공유 DB poller 소유권 분리로 root 락 이름 분리)")
    void outboxPollingJobLockRecordCreated() {
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Integer count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM shedlock WHERE name = 'rootOutboxPollingJob'",
                            Integer.class);
                    assertThat(count).isEqualTo(1);
                });
    }
}
