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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 공유 DB 전환기 outbox poller 소유권 분리 검증 (Product peel P4 완료 조건).
 * <p>product-service poller(aggregate-types=PRODUCT)가 자기 소유 PRODUCT 이벤트만 Kafka 로 발행하고
 * ORDER/PAYMENT 이벤트 행은 무시한다(root poller 가 발행). backlog gauge 의 집계 메서드도 자기 소유 aggregateType 만 센다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(IntegrationTestConfig.class)
@DisplayName("outbox poller 소유권 분리 통합 테스트 (PRODUCT 발행 · ORDER 무시)")
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
    @DisplayName("product poller 는 PRODUCT 이벤트만 발행하고 ORDER 이벤트는 PENDING 으로 무시한다 + gauge 는 PRODUCT 만 집계")
    void productPoller_publishesOnlyProduct_ignoresOrder() {
        outboxEventRepository.save(OutboxEvent.create(
                "PRODUCT", "p1", "product.test.probe", null, null, eventId -> "{}"));
        outboxEventRepository.save(OutboxEvent.create(
                "ORDER", "o1", "order.test.probe", null, null, eventId -> "{}"));

        // backlog gauge 집계 메서드(소유권 분리): 자기 소유 aggregateType 만 센다
        assertThat(outboxEventRepository.countByStatusAndAggregateTypeIn(
                OutboxEventStatus.PENDING, List.of("PRODUCT"))).isEqualTo(1);
        assertThat(outboxEventRepository.countByStatusAndAggregateTypeIn(
                OutboxEventStatus.PENDING, List.of("ORDER"))).isEqualTo(1);

        // product poller(aggregate-types=PRODUCT) 발행 → PRODUCT 만 PUBLISHED 로 비워진다
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            outboxPollingService.pollAndPublish();
            assertThat(outboxEventRepository.findPendingEvents(List.of("PRODUCT"), 100)).isEmpty();
        });

        // ORDER 이벤트는 product poller 가 무시 → 여전히 PENDING (root poller 소유)
        assertThat(outboxEventRepository.findPendingEvents(List.of("ORDER"), 100)).hasSize(1);
    }
}
