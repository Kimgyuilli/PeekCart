package com.peekcart.gateway.auth;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWKS last-known-good(LKG) · cold start · 실패 분류 회귀 (계획 P12 응답 행렬 · P19).
 *
 * <p>핵심 계약 3가지를 고정한다:
 * <ul>
 *   <li>캐시 hit → JWKS 가 죽어도 <b>정상</b>(LKG)</li>
 *   <li>unknown kid + refresh 성공했는데 부재 → {@code UnknownKidException}(→401)</li>
 *   <li>unknown kid + refresh 실패 → {@code JwksUnavailableException}(→503)</li>
 * </ul>
 */
@DisplayName("JwksKeyRegistry — LKG / cold start / 실패 분류")
class JwksKeyRegistryTest {

    private MockWebServer server;
    private KeyPair keyPair;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keyPair = g.generateKeyPair();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private JwksKeyRegistry registry(Duration cooldown) {
        JwtGatewayProperties props = new JwtGatewayProperties(
                server.url("/.well-known/jwks.json").toString(),
                Duration.ofSeconds(2), cooldown, Duration.ofMinutes(5));
        return new JwksKeyRegistry(WebClient.builder(), props);
    }

    private String jwksBody(String kid) {
        RSAPublicKey pub = (RSAPublicKey) keyPair.getPublic();
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String n = enc.encodeToString(toUnsigned(pub.getModulus().toByteArray()));
        String e = enc.encodeToString(toUnsigned(pub.getPublicExponent().toByteArray()));
        return """
                {"keys":[{"kty":"RSA","use":"sig","alg":"RS256","kid":"%s","n":"%s","e":"%s"}]}
                """.formatted(kid, n, e);
    }

    /** BigInteger.toByteArray() 의 선행 0 부호 바이트 제거 (JWKS 는 부호 없는 big-endian). */
    private static byte[] toUnsigned(byte[] bytes) {
        if (bytes.length > 1 && bytes[0] == 0) {
            byte[] trimmed = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
            return trimmed;
        }
        return bytes;
    }

    private MockResponse jwksResponse(String kid) {
        return new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(jwksBody(kid));
    }

    @Test
    @DisplayName("cold start: usable key 0 → hasUsableKey=false (readiness 미충족)")
    void coldStart_hasNoUsableKey() {
        JwksKeyRegistry registry = registry(Duration.ZERO);
        assertThat(registry.hasUsableKey()).isFalse();
    }

    @Test
    @DisplayName("최초 resolve 로 JWKS 적재 → hasUsableKey=true")
    void firstResolve_loadsKeys() {
        server.enqueue(jwksResponse("kid-a"));
        JwksKeyRegistry registry = registry(Duration.ZERO);

        StepVerifier.create(registry.resolve("kid-a"))
                .assertNext(key -> assertThat(key.getModulus())
                        .isEqualTo(((RSAPublicKey) keyPair.getPublic()).getModulus()))
                .verifyComplete();

        assertThat(registry.hasUsableKey()).isTrue();
    }

    @Test
    @DisplayName("LKG: 캐시 적재 후 JWKS 가 죽어도 known kid 는 정상 해석 + 캐시 유지")
    void knownKid_survivesJwksOutage() {
        server.enqueue(jwksResponse("kid-a"));
        JwksKeyRegistry registry = registry(Duration.ZERO);
        StepVerifier.create(registry.resolve("kid-a")).expectNextCount(1).verifyComplete();

        // 이후 모든 JWKS 호출은 500 — 그래도 known kid 는 네트워크를 타지 않는다
        server.enqueue(new MockResponse().setResponseCode(500));
        StepVerifier.create(registry.resolve("kid-a")).expectNextCount(1).verifyComplete();

        // 주기 갱신이 실패해도 캐시(LKG)는 보존된다
        StepVerifier.create(registry.refreshQuietly()).verifyComplete();
        assertThat(registry.hasUsableKey()).isTrue();
    }

    @Test
    @DisplayName("unknown kid + refresh 성공했으나 부재 → UnknownKid(401)")
    void unknownKid_afterSuccessfulRefresh_is401() {
        server.enqueue(jwksResponse("kid-a"));
        JwksKeyRegistry registry = registry(Duration.ZERO);

        StepVerifier.create(registry.resolve("kid-zzz"))
                .expectError(JwksKeyRegistry.UnknownKidException.class)
                .verify();
    }

    @Test
    @DisplayName("unknown kid + refresh 실패 → JwksUnavailable(503)")
    void unknownKid_refreshFailure_is503() {
        server.enqueue(new MockResponse().setResponseCode(500));
        JwksKeyRegistry registry = registry(Duration.ZERO);

        StepVerifier.create(registry.resolve("kid-a"))
                .expectError(JwksKeyRegistry.JwksUnavailableException.class)
                .verify();
    }

    @Test
    @DisplayName("kid 부재(null/blank) → UnknownKid(401), 네트워크 호출 없음")
    void blankKid_is401WithoutFetch() {
        JwksKeyRegistry registry = registry(Duration.ZERO);

        StepVerifier.create(registry.resolve(null))
                .expectError(JwksKeyRegistry.UnknownKidException.class)
                .verify();
        StepVerifier.create(registry.resolve("  "))
                .expectError(JwksKeyRegistry.UnknownKidException.class)
                .verify();

        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    @DisplayName("빈 keys 응답은 기존 캐시를 지우지 않는다 (LKG 보존)")
    void emptyKeysResponse_preservesCache() {
        server.enqueue(jwksResponse("kid-a"));
        JwksKeyRegistry registry = registry(Duration.ZERO);
        StepVerifier.create(registry.resolve("kid-a")).expectNextCount(1).verifyComplete();

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"keys\":[]}"));
        StepVerifier.create(registry.refreshQuietly()).verifyComplete();

        assertThat(registry.hasUsableKey()).isTrue();
        StepVerifier.create(registry.resolve("kid-a")).expectNextCount(1).verifyComplete();
    }

    @Test
    @DisplayName("cooldown: 연속 unknown kid 요청이 JWKS 를 폭주 호출하지 않는다")
    void refreshCooldown_throttlesFetch() {
        server.enqueue(jwksResponse("kid-a"));
        JwksKeyRegistry registry = registry(Duration.ofMinutes(1));

        StepVerifier.create(registry.resolve("kid-x"))
                .expectError(JwksKeyRegistry.UnknownKidException.class).verify();
        StepVerifier.create(registry.resolve("kid-y"))
                .expectError(JwksKeyRegistry.UnknownKidException.class).verify();
        StepVerifier.create(registry.resolve("kid-z"))
                .expectError(JwksKeyRegistry.UnknownKidException.class).verify();

        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("snapshot 교체: JWKS 에서 사라진 kid 는 즉시 무효화된다 (폐기/침해 키 잔존 금지, GW-2 c3:4)")
    void removedKid_isEvictedOnRefresh() {
        server.enqueue(jwksResponse("kid-old"));
        JwksKeyRegistry registry = registry(Duration.ZERO);
        StepVerifier.create(registry.resolve("kid-old")).expectNextCount(1).verifyComplete();
        assertThat(registry.knownKids()).containsExactly("kid-old");

        // User 가 kid-old 를 폐기하고 kid-new 만 게시
        server.enqueue(jwksResponse("kid-new"));
        StepVerifier.create(registry.refreshQuietly()).verifyComplete();

        assertThat(registry.knownKids()).containsExactly("kid-new");

        // 폐기된 kid 로 서명된 토큰은 더 이상 검증되면 안 된다 (refresh 후에도 부재 → 401 계열)
        server.enqueue(jwksResponse("kid-new"));
        StepVerifier.create(registry.resolve("kid-old"))
                .expectError(JwksKeyRegistry.UnknownKidException.class)
                .verify();
    }

    @Test
    @DisplayName("주기 갱신 직후 새 kid 가 등장하면 cooldown 밖에서 즉시 refresh 된다 (완료된 fetch 재사용 금지, GW-2 c3:6)")
    void completedFetchIsNotReused() {
        server.enqueue(jwksResponse("kid-a"));
        JwksKeyRegistry registry = registry(Duration.ZERO);
        StepVerifier.create(registry.refreshQuietly()).verifyComplete();
        assertThat(server.getRequestCount()).isEqualTo(1);

        // 회전으로 새 kid 등장 — 완료된 in-flight Mono 를 재사용하면 네트워크 호출 없이 UnknownKid 가 된다
        server.enqueue(jwksResponse("kid-rotated"));
        StepVerifier.create(registry.resolve("kid-rotated")).expectNextCount(1).verifyComplete();

        assertThat(server.getRequestCount()).isEqualTo(2);
    }
}
