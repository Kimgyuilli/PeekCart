package com.peekcart.product.observability;

import com.peekcart.support.IntegrationTestConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
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

/**
 * product-service 관측성 계약 회귀 (ADR-0009 §45-48 서비스별 복제).
 * <p>Product peel 로 root 에서 이관된 두 계약을 product-service 에서 검증한다:
 * (1) 상품 URI({@code /api/v1/products}) http_server_requests histogram bucket(S1) + application=product-service 태그(S2),
 * (2) 상품 목록 캐시("products") hit/miss 메트릭(D-014/L-005 — CacheConfig 이 product-service 소유).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@TestPropertySource(properties = {
        "management.endpoint.health.probes.enabled=true",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Testcontainers
@Import(IntegrationTestConfig.class)
@DisplayName("product-service 관측성 계약 회귀 테스트")
class ProductObservabilityMetricsIntegrationTest {

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
    @DisplayName("/api/v1/products 호출 후 /actuator/prometheus 에 application=product-service 태그 + 상품 URI histogram bucket 이 노출된다")
    void prometheus_exposesApplicationTagAndProductHistogram() {
        assertThat(restTemplate.getForEntity("/api/v1/products", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = prometheus.getBody();
        assertThat(body).isNotNull();
        assertThat(body)
                .as("ADR-0009 S2: 서비스별 application 태그")
                .contains("application=\"product-service\"");
        assertThat(body)
                .as("ADR-0009 S1 / D-001: 상품 URI http_server_requests histogram bucket")
                .containsPattern("http_server_requests_seconds_bucket\\{[^}]*uri=\"/api/v1/products\"[^}]*le=\"[^\"]+\"[^}]*\\}");
    }

    @Test
    @DisplayName("상품 목록 캐시 호출 후 /actuator/prometheus 에 cache hit/miss 메트릭이 노출된다 (D-014/L-005 — root 에서 이관)")
    void prometheus_exposesProductsCacheMetrics() {
        assertThat(restTemplate.getForEntity("/api/v1/products", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/api/v1/products", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);

        ResponseEntity<String> prometheus = restTemplate.getForEntity("/actuator/prometheus", String.class);
        assertThat(prometheus.getStatusCode()).isEqualTo(HttpStatus.OK);

        String body = prometheus.getBody();
        assertThat(body).isNotNull();
        assertCacheGetLine(body, "products", "miss");
        assertCacheGetLine(body, "products", "hit");
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
