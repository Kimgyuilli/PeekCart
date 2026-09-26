package com.peekcart.user.infrastructure.security;

import com.peekcart.global.filter.MdcFilter;
import com.peekcart.global.security.InternalTokenAuthenticationFilter;
import com.peekcart.internaltoken.InternalTokenContract;
import com.peekcart.global.jwt.RsaPublicKeyRegistry;
import com.peekcart.global.security.InternalGatewayPublicKeyRegistry;
import com.peekcart.internaltoken.InternalTokenFixtures;
import com.peekcart.support.InternalKeyFingerprint;
import com.peekcart.support.SharedContainers;
import com.peekcart.support.TestRsaKeys;
import com.peekcart.user.domain.model.User;
import com.peekcart.user.domain.repository.UserRepository;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * user-service 보안 통합 회귀 — Gateway 서명 내부 토큰 전환(ADR-0017 · PR3d).
 * Gateway 가 자기 개인키로 서명한 {@code X-Internal-Auth} 만 인증 근거이며, 평문 {@code X-User-*} 와
 * 사용자 {@code Authorization} 은 신원으로 취급하지 않는다(ADR-0014 D2-c exit).
 * <ul>
 *   <li>3-state: 토큰 부재→401(보호) / 유효 서명→인증 / 위조·만료·형식오류→401</li>
 *   <li>configurer 동등성: SecurityFilterChain 1개 · InternalTokenAuthenticationFilter 1회 · MdcFilter 1회 · actuator permitAll(S4)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(SharedContainers.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 테스트는 공유 fixture 키쌍을 쓴다(운영 dev 키의 개인키는 산출물에 없다).
        "app.internal-token.public-keys[0].kid=gw-test-2026",
        "app.internal-token.public-keys[0].location=classpath:internal-token/gateway-test-public.pem"
})
@DisplayName("user-service 보안 통합 테스트 (Gateway 서명 내부 토큰)")
class UserSecurityIntegrationTest {

    /**
     * user-service 는 여전히 사용자 토큰을 서명한다(JwtTokenSigner·JwkController) — RS256 키가 없으면 컨텍스트가
     * 부팅하지 못한다. 내부 토큰 인증은 이 키를 쓰지 않지만(키 도메인 분리, ADR-0017 D3), 서명 빈 생성을 위해 주입한다
     * (개인키 커밋 금지, ADR-0013 D2 — 런타임 생성).
     */
    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestRsaKeys.register(registry);
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired UserRepository userRepository;
    @Autowired Map<String, SecurityFilterChain> securityFilterChains;
    @Autowired RsaPublicKeyRegistry userKeyRegistry;
    @Autowired InternalGatewayPublicKeyRegistry internalKeyRegistry;

    private Long userId;

    @BeforeEach
    void setUp() {
        // getMe 가 실제 조회하므로 회원 행이 필요. RANDOM_PORT @SpringBootTest 는 롤백되지 않으므로 고유 이메일.
        User user = userRepository.save(
                User.create("sec-" + System.nanoTime() + "@peekcart.com", "hashed-pw", "보안테스트"));
        userId = user.getId();
    }

    @Test
    @DisplayName("내부 토큰 부재 요청은 401 로 거부된다 (3-state: 부재→보호경로 401)")
    void noToken_rejected() {
        ResponseEntity<String> res = restTemplate.getForEntity("/api/v1/users/me", String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("공개 endpoint /api/v1/auth/signup 은 토큰 없이도 허용된다 (permitAll)")
    void publicAuthEndpoint_permitAll() {
        SignupRequest body = new SignupRequest(
                "signup-" + System.nanoTime() + "@peekcart.com", "password123", "공개테스트");
        ResponseEntity<String> res = restTemplate.postForEntity("/api/v1/auth/signup", body, String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("유효 서명 내부 토큰은 인증되어 200 (3-state: 정상→인증)")
    void validSignedToken_authenticated() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, signed(userId, "USER", "fam-1"), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("위조 서명(미승인 키) → 401")
    void forgedSignature_rejected() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET,
                withToken(InternalTokenFixtures.mintForged(userId, "ADMIN")), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("만료된 내부 토큰 → 401")
    void expiredToken_rejected() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET,
                withToken(InternalTokenFixtures.mintExpired(userId, "USER", "fam-1")), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("fid 없는 내부 토큰 → 401 (SIGNED_ONLY 는 fid 필수)")
    void familyLessToken_rejected() {
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET,
                withToken(InternalTokenFixtures.mint(userId, "USER", null)), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("평문 X-User-* 직접 주입은 무시된다 → 401 (스푸핑 표면 0)")
    void plaintextHeaders_ignored() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", String.valueOf(userId));
        headers.set("X-User-Role", "ADMIN");
        headers.set("X-User-Family-Id", "evil");
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("직접 경로 Authorization: Bearer 는 인증 근거가 아니다 → 401 (Gateway 우회 차단)")
    void directBearerToken_rejected() {
        // 깨진 문자열이 아니라 *유효한* 사용자 access token 이어야 음성 대조군이 성립한다 —
        // 서비스에 사용자 토큰 검증 필터가 되살아나면 이 요청은 인증돼 테스트가 깨진다.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestRsaKeys.mintUserAccessToken(1L, "USER", "fam-1"));
        ResponseEntity<String> res = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SecurityFilterChain 은 1개, 내부 토큰 필터·MdcFilter 각 1회 등록 (configurer 동등성)")
    void singleChain_internalTokenAndMdcFilters() {
        assertThat(securityFilterChains).hasSize(1);
        SecurityFilterChain chain = securityFilterChains.values().iterator().next();
        long tokenFilterCount = chain.getFilters().stream()
                .filter(f -> f instanceof InternalTokenAuthenticationFilter).count();
        // MdcFilter 보존은 configurer 동등성의 일부 — 누락 시 traceId/userId MDC 가 조용히 사라진다(PR3c review #6).
        long mdcFilterCount = chain.getFilters().stream()
                .filter(f -> f instanceof MdcFilter).count();
        assertThat(tokenFilterCount).isEqualTo(1);
        assertThat(mdcFilterCount).isEqualTo(1);
    }

    @Test
    @DisplayName("키 도메인 분리: 부팅된 Environment 에서 두 레지스트리의 키 집합이 서로소다 (ADR-0017 D3)")
    void keyDomainsAreSeparated() {
        // 렌더된 YAML 이 아니라 *실제 바인딩 결과*를 본다 — 프로파일/env override 로 섞여도 잡힌다.
        assertThat(internalKeyRegistry.kids())
                .as("내부 토큰 공개키가 비면 서비스는 아무도 인증하지 못한다")
                .isNotEmpty();

        // 고정 fixture 키 하나만 대조하면, 내부 레지스트리에 *다른* 키가 바인딩되고 그 키가 User 쪽에도
        // 들어간 경우를 놓친다(diff 리뷰 c2:3). 두 집합 전체의 교집합이 비어 있어야 한다.
        Set<String> internalFingerprints = internalKeyRegistry.kids().stream()
                .map(internalKeyRegistry::find)
                .map(InternalKeyFingerprint::of)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> userFingerprints = userKeyRegistry.all().values().stream()
                .map(InternalKeyFingerprint::of)
                .collect(java.util.stream.Collectors.toSet());

        assertThat(internalFingerprints)
                .as("Gateway 서명 공개키가 app.jwt.rs256.public-keys 에 섞이면 JWKS 로 외부에 게시된다"
                        + " — kid 가 아니라 키 자체(SPKI fingerprint)로 확인한다")
                .doesNotContainAnyElementsOf(userFingerprints);
    }

    @Test
    @DisplayName("/actuator/health 는 비인증 permitAll 200 (게이트 c · ADR-0009 S4)")
    void actuatorHealth_permitAll() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private HttpEntity<Void> signed(long userId, String role, String familyId) {
        return withToken(InternalTokenFixtures.mint(userId, role, familyId));
    }

    private HttpEntity<Void> withToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalTokenContract.HEADER, token);
        return new HttpEntity<>(headers);
    }
}
