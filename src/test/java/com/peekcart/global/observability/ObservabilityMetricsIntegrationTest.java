package com.peekcart.global.observability;

import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.OutboxPollingService;
import com.peekcart.support.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;


import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@TestPropertySource(properties = "management.endpoint.health.probes.enabled=true")
@Testcontainers
@DisplayName("관측성 계약 회귀 테스트 (D-001/D-005 재발 방지)")
class ObservabilityMetricsIntegrationTest extends AbstractIntegrationTest {

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
    TestRestTemplate restTemplate;

    @Autowired
    OutboxEventRepository outboxEventRepository;

    @Autowired
    OutboxPollingService outboxPollingService;

    @Test
    @DisplayName("엔드포인트 호출 후 /actuator/prometheus에 histogram bucket + application 태그가 노출된다")
    void prometheusEndpoint_exposesHistogramBucketAndApplicationTag() {
        // Product peel: /api/v1/products 는 product-service 로 이동 → root 잔존 엔드포인트(actuator/health, permitAll)로
        // http_server_requests 메트릭을 생성한다(notification-service 관측성 테스트 선례). 상품 URI 별 histogram 은 product-service 가 검증.
        ResponseEntity<String> businessResponse = restTemplate.getForEntity("/actuator/health", String.class);
        assertThat(businessResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> prometheusResponse = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = prometheusResponse.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as("common tag application=peekcart 이 모든 메트릭에 부여되어야 한다 (P0-B: base management.metrics.tags.application)")
                .contains("application=\"peekcart\"");

        assertThat(body)
                .as("http_server_requests histogram bucket 이 노출되어야 한다 (D-001: MetricsConfig MeterFilter)")
                .contains("http_server_requests_seconds_bucket");
    }

    @Test
    @DisplayName("outbox 발행 후 /actuator/prometheus에 backlog gauge + publish timer 메트릭이 노출된다 (D-014/L-009)")
    void prometheusEndpoint_exposesOutboxPipelineMetrics() {
        // probe 토픽(consumer 없음)으로 PENDING 이벤트 1건 발행 → publish success 경로 생성.
        // Product peel: root poller 는 ORDER/PAYMENT aggregateType 만 발행하므로 probe 도 ORDER 로 둔다.
        outboxEventRepository.save(OutboxEvent.create(
                "ORDER", "probe", "observability.outbox.probe", null, null, eventId -> "{}"));
        outboxPollingService.pollAndPublish();

        ResponseEntity<String> prometheusResponse = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = prometheusResponse.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as("backlog gauge 는 status=pending / status=failed 두 시계열로 노출되어야 한다")
                .containsPattern("outbox_backlog\\{[^}]*status=\"pending\"[^}]*\\}")
                .containsPattern("outbox_backlog\\{[^}]*status=\"failed\"[^}]*\\}");

        assertThat(outboxPublishCount(body, "success"))
                .as("발행 성공 1건 이상이 outbox_publish Timer 의 result=success 시계열로 집계되어야 한다")
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    @DisplayName("actuator exposure 화이트리스트가 정확히 health, prometheus 만 데이터를 노출한다 (D5-V3)")
    void actuatorExposure_whitelistsExactlyHealthAndPrometheus() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .as("health 는 노출 (whitelisted)")
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .as("prometheus 는 노출 (whitelisted)")
                .isEqualTo(HttpStatus.OK);
        // info / env 는 exposure include 미포함 + PUBLIC_URLS 미포함 — 200 으로 데이터를 내면
        // 화이트리스트 (exposure include + SecurityConfig PUBLIC_URLS) 양쪽이 동시에 깨진 회귀.
        // 단일 레이어 (exposure 또는 SecurityConfig 한쪽만 깨짐) 도 200 이 아닌 응답으로 차단.
        assertThat(restTemplate.getForEntity("/actuator/info", String.class).getStatusCode())
                .as("info 는 데이터 미노출 (exposure include + SecurityConfig 이중 보호)")
                .isNotEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .as("env 는 데이터 미노출")
                .isNotEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("/actuator/health/** 가 인증 없이 200 응답한다 (D5-V4 — K8s liveness/readiness Probe 의존)")
    void actuatorHealth_noAuthRequired() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private static double outboxPublishCount(String prometheusBody, String result) {
        return prometheusBody.lines()
                .filter(line -> line.startsWith("outbox_publish_seconds_count"))
                .filter(line -> line.contains("result=\"" + result + "\""))
                .mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
                .sum();
    }

}
