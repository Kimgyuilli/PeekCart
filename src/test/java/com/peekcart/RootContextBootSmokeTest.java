package com.peekcart;

import com.peekcart.global.port.SlackPort;
import com.peekcart.global.slack.SlackNotificationClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.SecurityFilterChain;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * root app 부팅 스모크 (PR2a-2b 게이트 b · 게이트 j root 측).
 * <p>SlackPort 가 :common 으로 이동(SlackNotificationClient) + KafkaConfig 가 그 SlackPort 를 주입하는
 * 조합으로 root 컨텍스트가 부팅됨을 검증한다. IntegrationTestConfig(no-op 목) 를 import 하지 않아
 * 실제 :common SlackNotificationClient 가 SlackPort 빈으로 로드됨을 확인한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureObservability
@Testcontainers
@DisplayName("root app 부팅 스모크 (SlackPort→:common, 게이트 b/j)")
class RootContextBootSmokeTest {

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

    @Autowired SlackPort slackPort;
    @Autowired TestRestTemplate restTemplate;
    @Autowired Map<String, SecurityFilterChain> securityFilterChains;

    @Test
    @DisplayName("SlackPort 빈은 :common 의 SlackNotificationClient 다 (KafkaConfig 주입 무회귀)")
    void slackPort_isCommonSlackNotificationClient() {
        assertThat(slackPort).isInstanceOf(SlackNotificationClient.class);
    }

    @Test
    @DisplayName("root SecurityFilterChain 은 1개 · 비즈니스 endpoint 미인증 거부 · actuator permitAll (게이트 j)")
    void rootSecurity_regression() {
        assertThat(securityFilterChains).hasSize(1);

        // 비즈니스 endpoint 미인증 거부 (Order peel: /api/v1/orders → order-service 로 이동 → root 잔여 /api/v1/payments 로 검증)
        assertThat(restTemplate.getForEntity("/api/v1/payments", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        // actuator permitAll (ADR-0009 S4 단일 소유 — root 도 ActuatorSecurityConfig 로 합침)
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(restTemplate.getForEntity("/actuator/prometheus", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }
}
