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
import com.peekcart.support.TestRsaKeys;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.peekcart.user.application.AuthService;
import com.peekcart.user.application.dto.TokenResult;
import com.peekcart.user.domain.exception.RefreshTokenReuseException;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    /** 개인키 커밋 금지(ADR-0013 D2) — 런타임 생성 키쌍으로 서명/검증 키를 주입한다. */
    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestRsaKeys.register(registry);
    }

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


    // ---------- S9: 인증 메트릭 중 User 소유분 (ADR-0009 S9 · ADR-0024 D4) ----------

    @Autowired
    AuthService authService;

    @Autowired
    MeterRegistry meterRegistry;

    @Autowired
    PlatformTransactionManager transactionManager;

    private double counter(String name) {
        return meterRegistry.find(name).counter() == null ? 0.0 : meterRegistry.get(name).counter().count();
    }

    private TokenResult signup(String email) {
        return authService.signup(new SignupRequest(email, "password123!", "tester"));
    }

    @Test
    @DisplayName("S9 — reuse 감지 시 auth.token.reuse.detected +1 (무효화된 토큰 재제시)")
    void reuseDetected_isCounted() {
        double before = counter("auth.token.reuse.detected");
        TokenResult issued = signup("reuse-metric@peekcart.test");

        authService.refresh(issued.refreshToken());                 // 정상 로테이션
        authService.refresh(issued.refreshToken());                 // grace 1회 허용
        assertThatThrownBy(() -> authService.refresh(issued.refreshToken()))
                .as("grace 소진 후 재제시는 reuse 판정")
                .isInstanceOf(RefreshTokenReuseException.class);

        assertThat(counter("auth.token.reuse.detected"))
                .as("reuse 는 S9 에서 유일하게 alert 로 올린 신호다 — 안 세면 alert 가 죽는다")
                .isEqualTo(before + 1);
    }

    @Test
    @DisplayName("S9 — 정상 로테이션만으로는 reuse 카운터가 오르지 않는다")
    void normalRotation_doesNotCountReuse() {
        double before = counter("auth.token.reuse.detected");
        TokenResult issued = signup("rotate-metric@peekcart.test");

        TokenResult rotated = authService.refresh(issued.refreshToken());
        authService.refresh(rotated.refreshToken());

        assertThat(counter("auth.token.reuse.detected")).isEqualTo(before);
    }

    @Test
    @DisplayName("S9 — 로그아웃 시 auth.logout +1")
    void logout_isCounted() {
        double before = counter("auth.logout");
        signup("logout-metric@peekcart.test");
        Long userId = userRepository.findByEmail("logout-metric@peekcart.test").orElseThrow().getId();

        authService.logout(userId, "fam-logout-1");

        assertThat(counter("auth.logout")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("S9 — 트랜잭션이 롤백되면 카운터도 오르지 않는다 (CommitAwareMetrics 계약)")
    void rolledBackLogout_isNotCounted() {
        // 본문에서 바로 증가시키면 DB 는 되돌아가는데 카운터만 남아, "무효화가 몇 건 있었나" 가
        // 실제 사건 수보다 부풀려진다. 그 숫자가 alert 근거이므로 부풀면 alert 가 거짓이 된다.
        double before = counter("auth.logout");
        signup("rollback-metric@peekcart.test");
        Long userId = userRepository.findByEmail("rollback-metric@peekcart.test").orElseThrow().getId();

        new TransactionTemplate(transactionManager).execute(status -> {
            authService.logout(userId, "fam-rollback-1");
            status.setRollbackOnly();
            return null;
        });

        assertThat(counter("auth.logout"))
                .as("롤백된 로그아웃이 집계되면 메트릭이 DB 와 어긋난다")
                .isEqualTo(before);
    }

    @Autowired
    UserRepository userRepository;
}
