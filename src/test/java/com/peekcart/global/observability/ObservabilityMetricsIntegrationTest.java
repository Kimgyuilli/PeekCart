package com.peekcart.global.observability;

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

import java.util.List;

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

    @Test
    @DisplayName("비즈니스 엔드포인트 호출 후 /actuator/prometheus에 histogram bucket + application 태그가 노출된다")
    void prometheusEndpoint_exposesHistogramBucketAndApplicationTag() {
        ResponseEntity<String> businessResponse = restTemplate.getForEntity("/api/v1/products", String.class);
        assertThat(businessResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<String> prometheusResponse = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = prometheusResponse.getBody();
        assertThat(body).isNotNull();

        assertThat(body)
                .as("common tag application=peekcart 이 모든 메트릭에 부여되어야 한다 (P0-B: base management.metrics.tags.application)")
                .contains("application=\"peekcart\"");

        assertThat(body)
                .as("비즈니스 URI 에 대한 http_server_requests histogram bucket 이 노출되어야 한다 (D-001: MetricsConfig MeterFilter)")
                .containsPattern("http_server_requests_seconds_bucket\\{[^}]*uri=\"/api/v1/products\"[^}]*le=\"[^\"]+\"[^}]*\\}");
    }

    @Test
    @DisplayName("상품 목록 캐시 호출 후 /actuator/prometheus에 cache hit/miss 메트릭이 노출된다 (D-014/L-005)")
    void prometheusEndpoint_exposesCacheMetrics() {
        assertThat(restTemplate.getForEntity("/api/v1/products", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/api/v1/products", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> prometheusResponse = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheusResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = prometheusResponse.getBody();
        assertThat(body).isNotNull();

        assertCacheGetLine(body, "products", "miss");
        assertCacheGetLine(body, "products", "hit");
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

    private static void assertCacheGetLine(String prometheusBody, String cacheName, String result) {
        List<String> lines = prometheusBody.lines()
                .filter(line -> line.startsWith("cache_gets_total"))
                .filter(line -> line.contains("name=\"" + cacheName + "\""))
                .filter(line -> line.contains("result=\"" + result + "\""))
                .toList();

        assertThat(lines)
                .as("cache_gets_total name=%s result=%s 는 중복 없이 한 시계열만 노출되어야 한다", cacheName, result)
                .hasSize(1);
        assertThat(lines.get(0))
                .as("Spring Boot CacheMetricsAutoConfiguration 이 CacheManager 빈 이름을 cache_manager 라벨로 사용한다")
                .contains("cache_manager=\"cacheManager\"");
    }
}
