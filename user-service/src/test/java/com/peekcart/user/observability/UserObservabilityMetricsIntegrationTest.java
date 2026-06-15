package com.peekcart.user.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-service 관측성 계약 회귀 (ADR-0009 §45-48 서비스별 복제 · PR2b/U8).
 * application= 태그 값(S2)·histogram bucket(S1)·prometheus 노출(S3)·health permitAll(S4)·exposure 화이트리스트.
 * <p>User 는 Kafka 미사용(UserApplication 이 KafkaAutoConfiguration 제외) → Kafka 컨테이너 없음.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@TestPropertySource(properties = {
        "management.endpoint.health.probes.enabled=true",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
@DisplayName("user-service 관측성 계약 회귀 테스트")
class UserObservabilityMetricsIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    @DisplayName("/actuator/prometheus 에 application=user-service 태그와 histogram bucket 이 노출된다")
    void prometheus_exposesApplicationTagAndHistogramBucket() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = prometheus.getBody();
        assertThat(body).isNotNull();
        assertThat(body)
                .as("ADR-0009 S2: 서비스별 application 태그")
                .contains("application=\"user-service\"");
        assertThat(body)
                .as("ADR-0009 S1: http_server_requests histogram bucket 노출 (MetricsConfig MeterFilter)")
                .contains("http_server_requests_seconds_bucket");
    }

    @Test
    @DisplayName("actuator exposure 화이트리스트가 health, prometheus 만 데이터를 노출한다")
    void actuatorExposure_whitelistsHealthAndPrometheus() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/info", String.class).getStatusCode())
                .as("info 는 미노출")
                .isNotEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/env", String.class).getStatusCode())
                .as("env 는 미노출")
                .isNotEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("/actuator/health/** 가 인증 없이 200 응답한다 (K8s Probe)")
    void actuatorHealth_noAuthRequired() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/health/liveness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/health/readiness", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
