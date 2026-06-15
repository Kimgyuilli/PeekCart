package com.peekcart.notification.infrastructure.security;

import com.peekcart.global.jwt.JwtFilter;
import com.peekcart.support.IntegrationTestConfig;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Notification 서비스 보안 통합 회귀 (ADR-0014 D1 · ADR-0011 §D2 · ADR-0009 S4).
 * <ul>
 *   <li>게이트 g: 미인증 거부 / blacklist hit 거부 / miss(정상 토큰) 통과. (Redis 실패 fail-closed 는
 *       common-auth {@code RedisTokenBlacklistLookupAdapterTest} 단위 회귀에서 검증)</li>
 *   <li>게이트 h: root signer 와 동일 HS256/{@code app.jwt.secret} 으로 서명한 토큰을 notification verifier 가 검증</li>
 *   <li>게이트 j: 모듈당 {@code SecurityFilterChain} 1개 · {@code JwtFilter} 1회 등록</li>
 *   <li>게이트 c: {@code /actuator/health} 비인증 permitAll(S4 단일 소유)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Import(IntegrationTestConfig.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("Notification 보안 통합 테스트")
class NotificationSecurityIntegrationTest {

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

    @Autowired TestRestTemplate restTemplate;
    @Autowired RedisTemplate<String, String> redisTemplate;
    @Autowired Map<String, SecurityFilterChain> securityFilterChains;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    @Test
    @DisplayName("미인증 요청은 401 로 거부된다 (게이트 g — 미인증)")
    void unauthenticated_rejected() {
        ResponseEntity<String> res = restTemplate.getForEntity("/api/v1/notifications", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("root signer 와 동일 app.jwt.secret 서명 토큰은 notification verifier 가 검증해 200 (게이트 h — cross-module)")
    void rootSignedToken_accepted() {
        String token = signAccessToken(1L, "USER");
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.GET, bearer(token), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("blacklist 에 등록된 토큰은 거부된다 (게이트 g — hit)")
    void blacklistedToken_rejected() {
        String token = signAccessToken(2L, "USER");
        redisTemplate.opsForValue().set("bl:" + token, "1");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/notifications", HttpMethod.GET, bearer(token), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SecurityFilterChain 은 정확히 1개, JwtFilter 는 1회만 등록된다 (게이트 j)")
    void singleChain_singleJwtFilter() {
        assertThat(securityFilterChains).hasSize(1);
        SecurityFilterChain chain = securityFilterChains.values().iterator().next();
        long jwtFilterCount = chain.getFilters().stream().filter(f -> f instanceof JwtFilter).count();
        assertThat(jwtFilterCount).isEqualTo(1);
    }

    @Test
    @DisplayName("/actuator/health 는 비인증 permitAll 200 (게이트 c · ADR-0009 S4)")
    void actuatorHealth_permitAll() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private HttpEntity<Void> bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return new HttpEntity<>(headers);
    }

    /** root {@code JwtTokenSigner} 와 동일한 claim 구조·HS256·app.jwt.secret 으로 액세스 토큰을 발급한다. */
    private String signAccessToken(Long userId, String role) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 1_800_000))
                .signWith(key)
                .compact();
    }
}
