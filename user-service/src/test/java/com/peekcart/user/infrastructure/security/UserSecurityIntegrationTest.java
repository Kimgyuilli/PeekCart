package com.peekcart.user.infrastructure.security;

import com.peekcart.global.auth.TokenHasher;
import com.peekcart.global.jwt.JwtFilter;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-service 보안 통합 회귀 (ADR-0014 D1 · ADR-0011 §D2 · ADR-0009 S4 · PR2b/U8).
 * <ul>
 *   <li>게이트 g: 미인증 거부 · blacklist 신키(auth:blacklist:&lt;hash&gt;) hit 거부 · legacy(bl:&lt;token&gt;) hit 거부 ·
 *       miss 통과. (Redis 실패 fail-closed 는 common-auth {@code RedisTokenBlacklistLookupAdapterTest} 단위 회귀)</li>
 *   <li>게이트 h: user-service signer 와 동일 HS256/{@code app.jwt.secret} 토큰을 common-auth verifier 가 검증</li>
 *   <li>게이트 j: 모듈당 {@code SecurityFilterChain} 1개 · {@code JwtFilter} 1회 등록</li>
 *   <li>게이트 c: {@code /actuator/health} 비인증 permitAll(S4 단일 소유)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("user-service 보안 통합 테스트")
class UserSecurityIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Autowired TestRestTemplate restTemplate;
    @Autowired RedisTemplate<String, String> redisTemplate;
    @Autowired UserRepository userRepository;
    @Autowired Map<String, SecurityFilterChain> securityFilterChains;

    @Value("${app.jwt.secret}")
    String jwtSecret;

    private Long userId;

    @BeforeEach
    void setUp() {
        // @PreAuthorize 통과 후 getMe 가 실제 조회하므로 회원 행이 필요.
        // RANDOM_PORT @SpringBootTest 는 롤백되지 않으므로 메서드마다 고유 이메일로 unique 제약 충돌 회피.
        User user = userRepository.save(
                User.create("sec-" + System.nanoTime() + "@peekcart.com", "hashed-pw", "보안테스트"));
        userId = user.getId();
    }

    @Test
    @DisplayName("미인증 요청은 401 로 거부된다 (게이트 g — 미인증)")
    void unauthenticated_rejected() {
        ResponseEntity<String> res = restTemplate.getForEntity("/api/v1/users/me", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("공개 endpoint /api/v1/auth/signup 은 비인증 허용된다 (게이트 j — PUBLIC_URLS permitAll)")
    void publicAuthEndpoint_permitAll() {
        SignupRequest body = new SignupRequest(
                "signup-" + System.nanoTime() + "@peekcart.com", "password123", "공개테스트");
        ResponseEntity<String> res = restTemplate.postForEntity("/api/v1/auth/signup", body, String.class);
        assertThat(res.getStatusCode())
                .as("permitAll 이면 인증 없이 접근해 201 까지 도달")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("동일 app.jwt.secret 서명 토큰은 common-auth verifier 가 검증해 200 (게이트 h · miss 통과)")
    void validSignedToken_accepted() {
        String token = signAccessToken(userId, "USER");
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, bearer(token), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("신키 auth:blacklist:<hash> 에 등록된 토큰은 거부된다 (게이트 g — 신키 hit)")
    void newKeyBlacklistedToken_rejected() {
        String token = signAccessToken(userId, "USER");
        redisTemplate.opsForValue().set("auth:blacklist:" + TokenHasher.sha256Hex(token), "1");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, bearer(token), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("legacy bl:<token> 에 등록된 토큰도 dual-read 로 거부된다 (게이트 g — legacy hit)")
    void legacyBlacklistedToken_rejected() {
        String token = signAccessToken(userId, "USER");
        redisTemplate.opsForValue().set("bl:" + token, "1");

        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, bearer(token), String.class);
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

    /** user-service {@code JwtTokenSigner} 와 동일한 claim 구조·HS256·app.jwt.secret 으로 액세스 토큰을 발급한다. */
    private String signAccessToken(Long uid, String role) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(String.valueOf(uid))
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 1_800_000))
                .signWith(key)
                .compact();
    }
}
