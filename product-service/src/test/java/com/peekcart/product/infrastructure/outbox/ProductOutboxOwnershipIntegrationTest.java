package com.peekcart.product.infrastructure.outbox;

import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.OutboxEventStatus;
import com.peekcart.global.outbox.OutboxPollingService;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * DB-per-service(구현 ② PR2) outbox poller 발행 범위 검증.
 * <p>스키마 물리 분리로 소유권이 보장되므로 poller 는 더 이상 aggregateType allowlist 로 필터하지 않고,
 * 자기 스키마(peekcart_product)의 PENDING 이벤트를 aggregateType 무관하게 전부 발행한다(B8b allowlist 제거).
 * backlog gauge 집계 메서드도 자기 스키마 전체를 센다(countByStatus).
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("outbox poller 통합 테스트 (DB-per-service: 자기 스키마 PENDING 전체 발행)")
class ProductOutboxOwnershipIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired OutboxEventRepository outboxEventRepository;
    @Autowired OutboxPollingService outboxPollingService;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("poller 는 자기 스키마의 PENDING 을 aggregateType 무관하게 전부 발행한다 (allowlist 제거) + gauge 는 자기 스키마 전체 집계")
    void poller_publishesAllPendingInOwnSchema() {
        outboxEventRepository.save(OutboxEvent.create(
                "PRODUCT", "p1", "product.test.probe", null, null, eventId -> "{}"));
        // 다른 aggregateType 행도 더 이상 필터되지 않고 발행된다(스키마 분리가 소유권을 보장 → allowlist 불필요).
        outboxEventRepository.save(OutboxEvent.create(
                "ORDER", "o1", "order.test.probe", null, null, eventId -> "{}"));

        // backlog gauge 집계 메서드: 자기 스키마 전체를 센다(소유권 분리는 스키마가 보장).
        assertThat(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).isEqualTo(2);

        // poller 발행 → 자기 스키마의 PENDING 이 모두 비워진다(aggregateType 무관).
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            outboxPollingService.pollAndPublish();
            assertThat(outboxEventRepository.findPendingEvents(100)).isEmpty();
        });

        assertThat(outboxEventRepository.countByStatus(OutboxEventStatus.PENDING)).isZero();
    }
}
