package com.peekcart.product.infrastructure.security;

import com.peekcart.global.filter.MdcFilter;
import com.peekcart.global.security.InternalTokenAuthenticationFilter;
import com.peekcart.internaltoken.InternalTokenContract;
import com.peekcart.global.jwt.RsaPublicKeyRegistry;
import com.peekcart.global.security.InternalGatewayPublicKeyRegistry;
import com.peekcart.internaltoken.InternalTokenFixtures;
import com.peekcart.support.InternalKeyFingerprint;
import com.peekcart.support.SharedContainers;
import com.peekcart.support.TestRsaKeys;
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
 * product-service 보안 통합 회귀 — Gateway 서명 내부 토큰(ADR-0017 · PR3d).
 *
 * <p>5서비스 전부 같은 계약을 만족해야 한다(계획 P9 review #7 — 2개만 덮여 있던 공백 해소):
 * 토큰 부재→401 / 유효 서명→인증 / 위조·만료→401 / 평문 {@code X-User-*}·직접 {@code Authorization} 무시.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 테스트는 공유 fixture 키쌍을 쓴다(운영 dev 키의 개인키는 산출물에 없다).
        "app.internal-token.public-keys[0].kid=gw-test-2026",
        "app.internal-token.public-keys[0].location=classpath:internal-token/gateway-test-public.pem"
})
@Import(SharedContainers.class)
@DisplayName("product-service 보안 통합 테스트 (Gateway 서명 내부 토큰)")
class ProductSecurityIntegrationTest {

    private static final String PROTECTED_PATH = "/api/v1/admin/products";

    /**
     * 사용자 access token 검증키를 <b>일부러</b> 등록한다 — 서비스가 Bearer 를 다시 인증하기 시작하면
     * directBearerToken_rejected 가 실제로 실패하도록 만드는 음성 대조군 배선이다.
     * (운영 설정에는 이 서비스의 app.jwt.rs256 이 없다 — PR3d 에서 죽은 설정으로 제거했다.)
     */
    @DynamicPropertySource
    static void userTokenVerificationKeys(DynamicPropertyRegistry registry) {
        TestRsaKeys.register(registry);
    }

    @Autowired TestRestTemplate restTemplate;
    @Autowired Map<String, SecurityFilterChain> securityFilterChains;
    @Autowired RsaPublicKeyRegistry userKeyRegistry;
    @Autowired InternalGatewayPublicKeyRegistry internalKeyRegistry;

    @Test
    @DisplayName("내부 토큰 부재 → 401")
    void noToken_rejected() {
        assertThat(get(new HttpHeaders()).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("유효 서명 내부 토큰 → 인증 통과 (METHOD_NOT_ALLOWED — 401/403/5xx 배제)")
    void validSignedToken_authenticated() {
        // /api/v1/admin/products 는 POST/PUT/DELETE 만 있다 — 인증을 통과해야 비로소 디스패처까지 도달해
        // 405 가 된다. 무토큰이면 시큐리티가 먼저 401 을 내므로, 405 자체가 "인증 통과" 의 결정적 증거다.
        // (401 이 아님만 확인하면 405·403·5xx 가 전부 그린이 되는 vacuous 대조군이 된다)
        assertThat(get(withToken(InternalTokenFixtures.mint(1L, "ADMIN", "fam-1"))).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    @Test
    @DisplayName("위조 서명(미승인 키) → 401")
    void forgedSignature_rejected() {
        assertThat(get(withToken(InternalTokenFixtures.mintForged(1L, "ADMIN"))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("만료된 내부 토큰 → 401")
    void expiredToken_rejected() {
        assertThat(get(withToken(InternalTokenFixtures.mintExpired(1L, "ADMIN", "fam-1"))).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("평문 X-User-* 직접 주입은 무시된다 → 401 (스푸핑 표면 0)")
    void plaintextHeaders_ignored() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-User-Id", "999");
        headers.set("X-User-Role", "ADMIN");
        headers.set("X-User-Family-Id", "evil");
        assertThat(get(headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("직접 경로 Authorization: Bearer 는 인증 근거가 아니다 → 401 (Gateway 우회 차단)")
    void directBearerToken_rejected() {
        // 깨진 문자열이 아니라 *유효한* 사용자 access token 이어야 음성 대조군이 성립한다 —
        // 서비스에 사용자 토큰 검증 필터가 되살아나면 이 요청은 인증돼 테스트가 깨진다.
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(TestRsaKeys.mintUserAccessToken(1L, "USER", "fam-1"));
        assertThat(get(headers).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("SecurityFilterChain 1개 · 내부 토큰 필터 1회 · MdcFilter 1회 (configurer 동등성)")
    void singleChain_filtersPreserved() {
        assertThat(securityFilterChains).hasSize(1);
        SecurityFilterChain chain = securityFilterChains.values().iterator().next();
        assertThat(chain.getFilters().stream().filter(f -> f instanceof InternalTokenAuthenticationFilter).count())
                .isEqualTo(1);
        assertThat(chain.getFilters().stream().filter(f -> f instanceof MdcFilter).count()).isEqualTo(1);
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
    @DisplayName("/actuator/health 는 비인증 permitAll 200 (ADR-0009 S4)")
    void actuatorHealth_permitAll() {
        assertThat(restTemplate.getForEntity("/actuator/health", String.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    private ResponseEntity<String> get(HttpHeaders headers) {
        return restTemplate.exchange(PROTECTED_PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    private HttpHeaders withToken(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(InternalTokenContract.HEADER, token);
        return headers;
    }
}
