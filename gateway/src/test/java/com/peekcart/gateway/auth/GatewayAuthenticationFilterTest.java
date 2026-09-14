package com.peekcart.gateway.auth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import com.peekcart.gateway.ratelimit.RateLimiterUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import com.peekcart.internaltoken.InternalTokenContract;
import com.peekcart.internaltoken.InternalTokenFixtures;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.springframework.core.io.ByteArrayResource;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gateway 인증 필터 계약 회귀 (계획 P12/P19 · PR3d P9).
 *
 * <p>고정하는 계약:
 * <ul>
 *   <li>외부 유입 {@code X-Internal-Auth}/{@code X-User-*} 는 <b>공개 경로 포함 항상</b> 제거(spoof 차단)</li>
 *   <li>검증 성공 시 <b>Gateway 서명 내부 토큰만</b> 주입(평문 신원 헤더 주입 0)</li>
 *   <li>{@code Authorization} 은 다운스트림으로 <b>전달하지 않는다</b>(ADR-0014 D2-c exit)</li>
 *   <li>family-less 신원 → 발행 거부 401(fail-closed)</li>
 *   <li>보호 경로 무토큰/무효토큰 → 401 · 공개 경로 → 익명 통과</li>
 *   <li>의존성 장애(Redis/JWKS) → <b>503</b>, 공개 경로여도 통과시키지 않음(fail-closed)</li>
 * </ul>
 */
@DisplayName("GatewayAuthenticationFilter — strip/서명주입 · 401/503 · 공개 경로")
class GatewayAuthenticationFilterTest {

    private static final String TOKEN = "valid.jwt.token";

    private GatewayJwtVerifier verifier;
    private TokenDenyLookup denyLookup;
    private GatewayAuthenticationFilter filter;
    private AtomicReference<ServerWebExchange> forwarded;
    /** S9 계측 검증용 — 실제 레지스트리를 쓴다(mock 이면 "무엇이 올라갔는가" 를 못 본다). */
    private SimpleMeterRegistry meterRegistry;
    private GatewayAuthMetrics metrics;

    @BeforeEach
    void setUp() {
        verifier = mock(GatewayJwtVerifier.class);
        denyLookup = mock(TokenDenyLookup.class);
        PublicEndpointProperties publicProps = new PublicEndpointProperties(List.of(
                "POST /api/v1/auth/login",
                "GET /api/v1/products",
                "GET /api/v1/products/**",
                "POST /api/v1/payments/webhook"));
        meterRegistry = new SimpleMeterRegistry();
        metrics = new GatewayAuthMetrics(meterRegistry);
        filter = new GatewayAuthenticationFilter(verifier, denyLookup, issuer(true), publicProps, metrics);
        forwarded = new AtomicReference<>();
    }

    /** 실제 서명기를 쓴다 — mock 이면 "무엇이 주입됐는가" 를 검증할 수 없다. */
    private static InternalTokenIssuer issuer(boolean requireFamilyId) {
        return new InternalTokenIssuer(new InternalTokenProperties(
                InternalTokenFixtures.KID,
                new ByteArrayResource(InternalTokenFixtures.gatewayPrivateKeyPem().getBytes(StandardCharsets.UTF_8)),
                30,
                requireFamilyId
        ), Clock.fixed(InternalTokenFixtures.ISSUED_AT, ZoneOffset.UTC));
    }

    /** 주입된 내부 토큰을 Gateway 공개키로 열어 본다(계약대로 서명됐는지 확인). */
    private static Claims parseInjected(HttpHeaders headers) {
        String token = headers.getFirst(InternalTokenContract.HEADER);
        assertThat(token).as("내부 토큰이 주입되지 않음").isNotBlank();
        return Jwts.parser()
                .verifyWith(InternalTokenFixtures.gatewayPublicKey())
                .clock(() -> Date.from(InternalTokenFixtures.ISSUED_AT))
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private GatewayFilterChain chain() {
        return exchange -> {
            forwarded.set(exchange);
            return Mono.empty();
        };
    }

    private ServerHttpRequest forwardedRequest() {
        assertThat(forwarded.get()).as("체인으로 전달되지 않음").isNotNull();
        return forwarded.get().getRequest();
    }

    private void stubValid(String familyId) {
        when(verifier.verify(anyString()))
                .thenReturn(Mono.just(new GatewayClaims(42L, "USER", familyId, Instant.now().plusSeconds(300))));
        when(denyLookup.isDenied(anyString(), any())).thenReturn(Mono.just(false));
    }

    @Nested
    @DisplayName("헤더 strip / 서명 주입")
    class Headers {

        @Test
        @DisplayName("검증 성공 → 평문 X-User-* 제거 + 서명 내부 토큰만 주입 (spoof 무력화)")
        void validToken_stripsSpoofAndInjectsSignedToken() {
            stubValid("fam-9");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .header("X-User-Id", "999")            // 외부 위조 시도
                            .header("X-User-Role", "ADMIN")        // 권한 상승 시도
                            .header("X-User-Family-Id", "evil"));

            filter.filter(exchange, chain()).block();

            HttpHeaders headers = forwardedRequest().getHeaders();
            assertThat(headers.containsKey("X-User-Id")).as("평문 신원 헤더는 더 이상 주입하지 않는다").isFalse();
            assertThat(headers.containsKey("X-User-Role")).isFalse();
            assertThat(headers.containsKey("X-User-Family-Id")).isFalse();

            Claims claims = parseInjected(headers);
            assertThat(claims.getSubject()).isEqualTo("42");
            assertThat(claims.get(InternalTokenContract.CLAIM_ROLE, String.class)).isEqualTo("USER");
            assertThat(claims.get(InternalTokenContract.CLAIM_FAMILY_ID, String.class)).isEqualTo("fam-9");
            assertThat(claims.getIssuer()).isEqualTo(InternalTokenContract.ISSUER);
        }

        @Test
        @DisplayName("외부에서 실어 온 X-Internal-Auth 는 통째로 교체된다 (위조 토큰 통과 금지)")
        void externalInternalTokenHeader_isReplaced() {
            stubValid("fam-9");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .header(InternalTokenContract.HEADER, "forged.internal.token"));

            filter.filter(exchange, chain()).block();

            HttpHeaders headers = forwardedRequest().getHeaders();
            assertThat(headers.get(InternalTokenContract.HEADER))
                    .as("외부 값이 남아 있으면 다운스트림이 어느 쪽을 믿을지 모호해진다")
                    .hasSize(1);
            assertThat(headers.getFirst(InternalTokenContract.HEADER)).isNotEqualTo("forged.internal.token");
            assertThat(parseInjected(headers).getSubject()).isEqualTo("42");
        }

        @Test
        @DisplayName("중복 X-Internal-Auth 헤더도 전부 제거 후 1개만 주입된다")
        void duplicateInternalTokenHeaders_areCollapsed() {
            stubValid("fam-9");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .header(InternalTokenContract.HEADER, "forged.one")
                            .header(InternalTokenContract.HEADER, "forged.two"));

            filter.filter(exchange, chain()).block();

            assertThat(forwardedRequest().getHeaders().get(InternalTokenContract.HEADER)).hasSize(1);
        }

        @Test
        @DisplayName("family-less 신원 → 발행 거부 401 (fail-closed, 신원 없이 통과시키지 않음)")
        void familyLessToken_isRefused401() {
            stubValid(null);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).as("발행 실패 시 업스트림으로 새면 안 된다").isNull();
        }

        @Test
        @DisplayName("require-family-id=false 전환기 설정이면 family-less 도 fid 없이 발행된다")
        void familyLessToken_isIssuedWhenNotRequired() {
            stubValid(null);
            GatewayAuthenticationFilter transitional = new GatewayAuthenticationFilter(
                    verifier, denyLookup, issuer(false),
                    new PublicEndpointProperties(List.of("GET /api/v1/products")), metrics);
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            transitional.filter(exchange, chain()).block();

            Claims claims = parseInjected(forwardedRequest().getHeaders());
            assertThat(claims.getSubject()).isEqualTo("42");
            assertThat(claims.get(InternalTokenContract.CLAIM_FAMILY_ID, String.class)).isNull();
        }

        @Test
        @DisplayName("공개 경로 + 무토큰 → 외부 X-User-* 와 X-Internal-Auth 는 여전히 제거된다")
        void publicPath_stillStripsSpoofedHeaders() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header("X-User-Id", "999")
                            .header("X-User-Role", "ADMIN")
                            .header(InternalTokenContract.HEADER, "forged.internal.token"));

            filter.filter(exchange, chain()).block();

            HttpHeaders headers = forwardedRequest().getHeaders();
            assertThat(headers.containsKey("X-User-Id")).isFalse();
            assertThat(headers.containsKey("X-User-Role")).isFalse();
            assertThat(headers.containsKey(InternalTokenContract.HEADER))
                    .as("공개 경로로 위조 내부 토큰이 새면 서명 검증 자체가 무의미해진다")
                    .isFalse();
        }

        @Test
        @DisplayName("Authorization 은 다운스트림으로 전달되지 않는다 (ADR-0014 D2-c exit)")
        void authorizationHeader_isNotForwarded() {
            stubValid("fam-1");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(forwardedRequest().getHeaders().containsKey(HttpHeaders.AUTHORIZATION))
                    .as("서비스가 더 이상 검증하지 않는 사용자 자격증명을 내부망에 퍼뜨리지 않는다")
                    .isFalse();
        }
    }

    @Nested
    @DisplayName("보호 경로")
    class Protected {

        @Test
        @DisplayName("무토큰 → 401")
        void noToken_401() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).as("거부된 요청이 업스트림으로 새면 안 된다").isNull();
        }

        @Test
        @DisplayName("무효 토큰 → 401")
        void invalidToken_401() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.error(new GatewayJwtVerifier.InvalidTokenException(AuthFailureReason.BAD_SIGNATURE, "bad")));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("deny hit(blacklist/family) → 401")
        void deniedToken_401() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.just(new GatewayClaims(42L, "USER", "fam-1", Instant.now().plusSeconds(300))));
            when(denyLookup.isDenied(anyString(), any())).thenReturn(Mono.just(true));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("Bearer 아닌 Authorization → 무토큰 취급 401")
        void nonBearerAuthorization_401() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("실행 순서 · 다운스트림 오류 분리")
    class OrderingAndDownstream {

        @Test
        @DisplayName("라우트 필터(order 1..n)보다 먼저 실행돼야 RateLimiter 가 검증된 값을 쓴다 (GW-2 c2:1)")
        void order_isBeforeRouteFilters() {
            assertThat(filter.getOrder())
                    .as("route 정의 필터는 order 1 부터 부여된다 — 그보다 작아야 strip/검증이 먼저 일어난다")
                    .isLessThan(1)
                    .isEqualTo(GatewayAuthenticationFilter.AUTH_FILTER_ORDER);
        }

        @Test
        @DisplayName("검증 성공 시 userId 를 exchange attribute 로 노출한다 (RateLimiter 가 헤더 대신 이걸 신뢰)")
        void exposesAuthenticatedUserIdAttribute() {
            stubValid("fam-1");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .header("X-User-Id", "999"));

            filter.filter(exchange, chain()).block();

            assertThat(forwarded.get().<String>getAttribute(
                    GatewayAuthenticationFilter.AUTHENTICATED_USER_ID_ATTR))
                    .as("위조 헤더(999)가 아니라 검증된 42 여야 한다")
                    .isEqualTo("42");
        }

        @Test
        @DisplayName("다운스트림 오류는 401 로 오분류되지 않고 전파된다 (GW-2 c2:2)")
        void downstreamError_isNotConvertedTo401() {
            stubValid("fam-1");
            GatewayFilterChain failing = exchange -> Mono.error(new IllegalStateException("upstream down"));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            StepVerifier.create(filter.filter(exchange, failing))
                    .expectError(IllegalStateException.class)
                    .verify();

            assertThat(exchange.getResponse().getStatusCode())
                    .as("업스트림 장애를 401 로 감추면 안 된다")
                    .isNotEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("공개 경로 무토큰 요청에서 체인은 정확히 한 번만 호출된다 (GW-2 c2:2 이중 호출)")
        void publicPath_chainInvokedExactlyOnce() {
            AtomicInteger calls = new AtomicInteger();
            GatewayFilterChain counting = exchange -> {
                calls.incrementAndGet();
                forwarded.set(exchange);
                return Mono.empty();
            };
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products"));

            filter.filter(exchange, counting).block();

            assertThat(calls.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("RateLimiter 백엔드 장애 → 503 (429 아님)")
        void rateLimiterUnavailable_is503() {
            stubValid("fam-1");
            GatewayFilterChain failing = exchange -> Mono.error(
                    new RateLimiterUnavailableException("redis down", null));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, failing).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    @Nested
    @DisplayName("의존성 장애 = 503 (401 로 강등하지 않음)")
    class Dependency {

        @Test
        @DisplayName("Redis 조회 실패 → 503")
        void redisDown_503() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.just(new GatewayClaims(42L, "USER", "fam-1", Instant.now().plusSeconds(300))));
            when(denyLookup.isDenied(anyString(), any()))
                    .thenReturn(Mono.error(new TokenDenyLookup.DenyLookupUnavailableException("down", null)));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("JWKS 장애 → 503")
        void jwksDown_503() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.error(new JwksKeyRegistry.JwksUnavailableException("down", null)));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        }

        @Test
        @DisplayName("공개 경로여도 의존성 장애는 익명 통과로 감추지 않는다 → 503")
        void publicPath_dependencyFailure_stillFailsClosed() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.just(new GatewayClaims(42L, "USER", "fam-1", Instant.now().plusSeconds(300))));
            when(denyLookup.isDenied(anyString(), any()))
                    .thenReturn(Mono.error(new TokenDenyLookup.DenyLookupUnavailableException("down", null)));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(forwarded.get()).isNull();
        }
    }

    @Nested
    @DisplayName("공개 경로")
    class Public {

        @Test
        @DisplayName("무토큰 공개 GET → 통과")
        void publicGet_noToken_passes() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products"));

            filter.filter(exchange, chain()).block();

            assertThat(forwarded.get()).isNotNull();
            assertThat(exchange.getResponse().getStatusCode()).isNull();
        }

        @Test
        @DisplayName("무토큰 공개 POST(login/webhook) → 통과")
        void publicPost_noToken_passes() {
            MockServerWebExchange login = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/auth/login"));
            filter.filter(login, chain()).block();
            assertThat(forwarded.get()).isNotNull();

            forwarded.set(null);
            MockServerWebExchange webhook = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/payments/webhook"));
            filter.filter(webhook, chain()).block();
            assertThat(forwarded.get()).isNotNull();
        }

        @Test
        @DisplayName("공개 경로 + 무효 토큰 → 401 (익명 강등 금지, GW-2 c2:4)")
        void publicPath_invalidToken_isRejected() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.error(new GatewayJwtVerifier.InvalidTokenException(AuthFailureReason.BAD_SIGNATURE, "bad")));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN)
                            .header("X-User-Id", "999"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("공개 경로 + deny hit 토큰 → 401 (로그아웃/reuse 무효화가 공개 경로로 우회되면 안 된다)")
        void publicPath_deniedToken_isRejected() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.just(new GatewayClaims(42L, "USER", "fam-1", Instant.now().plusSeconds(300))));
            when(denyLookup.isDenied(anyString(), any())).thenReturn(Mono.just(true));
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(forwarded.get()).isNull();
        }

        @Test
        @DisplayName("공개 경로 + 유효 토큰 → 통과하며 내부 토큰도 주입된다")
        void publicPath_validToken_injectsInternalToken() {
            stubValid("fam-3");
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/products/1")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN));

            filter.filter(exchange, chain()).block();

            assertThat(forwarded.get()).isNotNull();
            assertThat(parseInjected(forwardedRequest().getHeaders()).getSubject()).isEqualTo("42");
        }

        @Test
        @DisplayName("method 로 좁힌다: POST /api/v1/products 는 공개가 아니다 → 401")
        void productsPost_isNotPublic() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.post("/api/v1/products"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("admin 경로는 공개가 아니다 → 401")
        void adminProducts_isNotPublic() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/api/v1/admin/products"));

            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("S9 인증 실패 계측 (ADR-0024 D4)")
    class AuthFailureMetrics {

        private double count(AuthFailureReason reason) {
            return meterRegistry.get("auth.failure").tag("reason", reason.tag()).counter().count();
        }

        private MockServerWebExchange protectedRequest(boolean withToken) {
            MockServerHttpRequest.BaseBuilder<?> req = MockServerHttpRequest.get("/api/v1/orders");
            if (withToken) {
                req = MockServerHttpRequest.get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN);
            }
            return MockServerWebExchange.from((MockServerHttpRequest) req.build());
        }

        @Test
        @DisplayName("모든 reason series 는 요청 전에 이미 0 으로 등록돼 있다")
        void allReasonsRegisteredEagerly() {
            // 지연 등록이면 "아직 일어나지 않은 사유" 와 "계측이 빠진 사유" 가 구분되지 않는다.
            for (AuthFailureReason reason : AuthFailureReason.values()) {
                assertThat(count(reason)).as("reason=%s", reason.tag()).isZero();
            }
        }

        @Test
        @DisplayName("정상 통과 요청은 어떤 reason 도 올리지 않는다")
        void successfulRequest_incrementsNothing() {
            stubValid("fam-1");

            filter.filter(protectedRequest(true), chain()).block();

            assertThat(meterRegistry.get("auth.failure").counters().stream()
                    .mapToDouble(c -> c.count()).sum())
                    .as("성공 경로가 카운터를 건드리면 실패율이 통째로 거짓이 된다")
                    .isZero();
        }

        @Test
        @DisplayName("무토큰 → reason=missing_token 만 +1")
        void missingToken() {
            filter.filter(protectedRequest(false), chain()).block();

            assertThat(count(AuthFailureReason.MISSING_TOKEN)).isEqualTo(1.0);
            assertThat(count(AuthFailureReason.MALFORMED)).isZero();
        }

        @ParameterizedTest(name = "검증 실패 reason={0} → 같은 태그로 +1")
        @EnumSource(value = AuthFailureReason.class,
                names = {"MALFORMED", "BAD_SIGNATURE", "EXPIRED", "UNKNOWN_KID", "ALG_NOT_ALLOWED", "MISSING_EXP"})
        @DisplayName("검증기가 나른 사유가 그대로 태그가 된다 (뭉개면 red)")
        void verifierReason_isPreserved(AuthFailureReason reason) {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.error(new GatewayJwtVerifier.InvalidTokenException(reason, "rejected")));

            filter.filter(protectedRequest(true), chain()).block();

            assertThat(count(reason)).as("reason=%s", reason.tag()).isEqualTo(1.0);
            // 응답 헤더는 401 세부 사유를 합쳐서 돌려준다(검증 내부 상태를 요청자에게 알리지 않는다).
            assertThat(count(AuthFailureReason.MISSING_TOKEN)).isZero();
        }

        @Test
        @DisplayName("deny hit → reason=denied (서명오류와 같은 숫자가 되면 안 된다)")
        void denyHit() {
            stubValid("fam-1");
            when(denyLookup.isDenied(anyString(), any())).thenReturn(Mono.just(true));

            filter.filter(protectedRequest(true), chain()).block();

            assertThat(count(AuthFailureReason.DENIED)).isEqualTo(1.0);
            assertThat(count(AuthFailureReason.BAD_SIGNATURE)).isZero();
        }

        @Test
        @DisplayName("family-less 발행 거부 → reason=internal_token_refused")
        void issuanceRefused() {
            stubValid(null);

            filter.filter(protectedRequest(true), chain()).block();

            assertThat(count(AuthFailureReason.INTERNAL_TOKEN_REFUSED)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("의존성 장애(deny 조회 실패) → reason=dependency_unavailable (401 아님)")
        void dependencyUnavailable() {
            when(verifier.verify(anyString()))
                    .thenReturn(Mono.just(new GatewayClaims(42L, "USER", "fam-1", Instant.now().plusSeconds(300))));
            when(denyLookup.isDenied(anyString(), any()))
                    .thenReturn(Mono.error(new TokenDenyLookup.DenyLookupUnavailableException("redis down", null)));

            MockServerWebExchange exchange = protectedRequest(true);
            filter.filter(exchange, chain()).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(count(AuthFailureReason.DEPENDENCY_UNAVAILABLE)).isEqualTo(1.0);
            assertThat(count(AuthFailureReason.BAD_SIGNATURE))
                    .as("장애를 인증 실패로 집계하면 대시보드가 공격 신호로 읽힌다")
                    .isZero();
        }

        @Test
        @DisplayName("rate limiter 백엔드 장애 → reason=rate_limiter_unavailable (429 축과 분리)")
        void rateLimiterUnavailable() {
            stubValid("fam-1");
            GatewayFilterChain failing = exchange ->
                    Mono.error(new RateLimiterUnavailableException("redis down", null));

            MockServerWebExchange exchange = protectedRequest(true);
            filter.filter(exchange, failing).block();

            assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(count(AuthFailureReason.RATE_LIMITER_UNAVAILABLE)).isEqualTo(1.0);
        }
    }
}
