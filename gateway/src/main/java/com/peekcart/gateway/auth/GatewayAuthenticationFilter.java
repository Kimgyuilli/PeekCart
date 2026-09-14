package com.peekcart.gateway.auth;

import com.peekcart.gateway.ratelimit.RateLimiterUnavailableException;
import com.peekcart.internaltoken.InternalTokenContract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Gateway 인증 필터 (ADR-0013 D3 · 구현 ③ PR3a) — 검증 3단계.
 *
 * <ol>
 *   <li><b>헤더 strip</b>: 외부에서 유입된 {@code X-Internal-Auth}/{@code X-User-*} 를 <b>항상</b> 제거한다(공개 경로 포함).</li>
 *   <li><b>서명/만료 검증</b>(RS256 via JWKS — PR4 로 HMAC fallback 제거) → <b>blacklist/family deny</b>(Redis)</li>
 *   <li><b>내부 토큰 주입</b>(Gateway 서명, ADR-0017) + 검증된 userId 를 exchange attribute 로 노출(RateLimiter 용)</li>
 * </ol>
 *
 * <p><b>실행 순서</b>(GW-2 c2:1/c3:2): 반드시 라우트 필터(`RequestRateLimiter`)보다 <b>먼저</b> 실행돼야
 * 한다. 라우트 정의 필터는 order 1..n 을 받으므로 본 필터는 그보다 앞선 {@link #AUTH_FILTER_ORDER} 를
 * 쓴다. 뒤에 실행되면 RateLimiter 가 <b>검증 전 외부 {@code X-User-Id}</b> 를 키로 삼아, 헤더만 바꿔
 * 사용자별 제한을 무한 회피할 수 있다.
 *
 * <p><b>응답 계약</b>(계획 P12 행렬): 서명오류·만료·exp 부재·unknown kid·deny hit → <b>401</b> /
 * JWKS·Redis 의존성 장애 → <b>503</b> / rate limit 초과 → 429(RateLimiter 소관).
 *
 * <p><b>PR3d</b>: 다운스트림에 평문 신원을 넘기지 않는다 — Gateway 개인키로 서명한 짧은 수명 내부 토큰
 * ({@link InternalTokenContract#HEADER})만 주입하고, {@code Authorization} 전달도 중단한다
 * (ADR-0017 D1/D5 · ADR-0014 D2-c exit). 서비스는 사용자 토큰을 더 이상 검증하지 않으므로
 * Authorization 을 계속 넘기면 쓰이지 않는 자격증명을 불필요하게 전파하는 셈이 된다.
 */
@Component
public class GatewayAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthenticationFilter.class);

    /**
     * 라우트 정의 필터(order 1..n)보다 앞서고, 응답 기록 필터(NettyWriteResponseFilter, order -1)보다도
     * 앞서 감싸도록 음수 order 를 쓴다.
     */
    public static final int AUTH_FILTER_ORDER = -100;

    /** 검증이 끝난 userId — RateLimiter 가 <b>이 값만</b> 신뢰한다(요청 헤더 직접 신뢰 금지). */
    public static final String AUTHENTICATED_USER_ID_ATTR = "peekcart.gateway.authenticatedUserId";

    /**
     * 외부 유입을 항상 제거하는 헤더. 내부 토큰 헤더가 여기 포함돼야 외부에서 위조 토큰을 실어 보내도
     * 주입 전에 제거된다(공개 경로 포함). 평문 {@code X-User-*} 는 더 이상 주입하지 않지만,
     * 구버전 서비스가 남아 있는 롤아웃 구간을 위해 <b>strip 대상으로는 유지</b>한다.
     */
    static final List<String> TRUSTED_HEADERS =
            List.of(InternalTokenContract.HEADER, "X-User-Id", "X-User-Role", "X-User-Family-Id");

    private static final String BEARER_PREFIX = "Bearer ";

    private final GatewayJwtVerifier verifier;
    private final TokenDenyLookup denyLookup;
    private final InternalTokenIssuer internalTokenIssuer;
    private final List<PublicEndpointProperties.Rule> publicRules;
    private final GatewayAuthMetrics metrics;

    public GatewayAuthenticationFilter(GatewayJwtVerifier verifier,
                                       TokenDenyLookup denyLookup,
                                       InternalTokenIssuer internalTokenIssuer,
                                       PublicEndpointProperties publicEndpointProperties,
                                       GatewayAuthMetrics metrics) {
        this.verifier = verifier;
        this.denyLookup = denyLookup;
        this.internalTokenIssuer = internalTokenIssuer;
        this.publicRules = publicEndpointProperties.rules();
        this.metrics = metrics;
    }

    @Override
    public int getOrder() {
        return AUTH_FILTER_ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        boolean isPublic = isPublicEndpoint(request);
        String token = resolveToken(request);

        if (token == null) {
            // 토큰 미제시: 공개 경로는 익명 통과, 보호 경로는 401.
            return isPublic
                    ? proceed(chain, stripTrusted(exchange))
                    : reject(exchange, HttpStatus.UNAUTHORIZED, AuthFailureReason.MISSING_TOKEN);
        }

        // 토큰이 제시됐다면 공개 경로여도 반드시 검증한다(GW-2 c2:4): 만료·위조·deny 토큰을 익명으로
        // 강등해 통과시키면 로그아웃/reuse 무효화가 공개 경로에서 우회된다.
        return authenticate(exchange, token)
                // 인증 단계 오류만 여기서 처리한다. chain.filter 는 아래에서 별도로 호출하므로
                // 업스트림/라우팅 오류가 401 로 오분류되지 않는다(GW-2 c2:2).
                .flatMap(authenticated -> proceed(chain, authenticated))
                .onErrorResume(e -> isAuthFailure(e)
                        ? rejectByCause(exchange, e)
                        : Mono.error(e));
    }

    /** 검증 성공 시 신뢰 헤더가 주입된 exchange 를 반환한다. 실패는 error signal 로만 표현한다. */
    private Mono<ServerWebExchange> authenticate(ServerWebExchange exchange, String token) {
        return verifier.verify(token)
                .flatMap(claims -> denyLookup.isDenied(token, claims.familyId())
                        .flatMap(denied -> denied
                                ? Mono.<GatewayClaims>error(new GatewayJwtVerifier.InvalidTokenException(
                                        AuthFailureReason.DENIED, "차단된 토큰(blacklist/family deny)"))
                                : Mono.just(claims)))
                .map(claims -> withInternalToken(exchange, claims));
    }

    /**
     * 다운스트림 진행. rate limiter 의 의존성 장애만 503 으로 변환하고, 그 외 업스트림 오류는
     * 기본 오류 처리에 맡긴다(401 오분류 금지).
     */
    private Mono<Void> proceed(GatewayFilterChain chain, ServerWebExchange exchange) {
        return chain.filter(exchange)
                .onErrorResume(RateLimiterUnavailableException.class,
                        e -> reject(exchange, HttpStatus.SERVICE_UNAVAILABLE,
                                AuthFailureReason.RATE_LIMITER_UNAVAILABLE));
    }

    private static boolean isAuthFailure(Throwable e) {
        return e instanceof GatewayJwtVerifier.InvalidTokenException
                || e instanceof JwksKeyRegistry.JwksUnavailableException
                || e instanceof TokenDenyLookup.DenyLookupUnavailableException
                || e instanceof InternalTokenIssuer.IssuanceRefusedException;
    }

    private Mono<Void> rejectByCause(ServerWebExchange exchange, Throwable e) {
        // 의존성 장애(503)와 보안 판정 실패(401)를 분리한다 — 장애를 401 로 감추면
        // 클라이언트가 재인증을 시도하고 실제 원인이 가려진다.
        if (e instanceof TokenDenyLookup.DenyLookupUnavailableException
                || e instanceof JwksKeyRegistry.JwksUnavailableException) {
            return reject(exchange, HttpStatus.SERVICE_UNAVAILABLE, AuthFailureReason.DEPENDENCY_UNAVAILABLE);
        }
        if (e instanceof InternalTokenIssuer.IssuanceRefusedException) {
            // 사용자 토큰 자체는 유효하나 정책상 내부 토큰을 발행하지 않는 신원(예: family-less).
            // fail-closed — 신원 없이 다운스트림으로 보내지 않는다.
            return reject(exchange, HttpStatus.UNAUTHORIZED, AuthFailureReason.INTERNAL_TOKEN_REFUSED);
        }
        // 401 세부 사유는 예외가 나른다(서명오류/만료/unknown kid/alg/exp 부재/deny hit).
        // isAuthFailure 에 새 예외 타입이 추가되고 여기 분기를 빠뜨리면 MALFORMED 로 떨어진다 —
        // ClassCastException 으로 요청을 500 으로 만드는 것보다 낫고, 태그가 실제와 어긋나는 것은
        // 대시보드에서 드러난다.
        AuthFailureReason reason = (e instanceof GatewayJwtVerifier.InvalidTokenException invalid)
                ? invalid.reason()
                : AuthFailureReason.MALFORMED;
        return reject(exchange, HttpStatus.UNAUTHORIZED, reason);
    }

    private boolean isPublicEndpoint(ServerHttpRequest request) {
        HttpMethod method = request.getMethod();
        PathContainer path = request.getPath().pathWithinApplication();
        return publicRules.stream().anyMatch(rule -> rule.matches(method, path));
    }

    private static String resolveToken(ServerHttpRequest request) {
        String header = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String value = header.substring(BEARER_PREFIX.length()).trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    /** 외부 유입 신뢰 헤더 제거만 수행(주입 없음). */
    private static ServerWebExchange stripTrusted(ServerWebExchange exchange) {
        return exchange.mutate()
                .request(r -> r.headers(headers -> TRUSTED_HEADERS.forEach(headers::remove)))
                .build();
    }

    /**
     * 외부 헤더 제거 후 Gateway 서명 내부 토큰을 주입하고 RateLimiter 용 attribute 를 기록한다.
     *
     * <p>사용자 자격증명({@code Authorization})은 함께 제거한다 — 서비스는 더 이상 사용자 토큰을 검증하지
     * 않으므로(ADR-0014 D2-c exit) 계속 전달하면 쓰이지도 않는 Bearer 토큰이 내부망 전 구간에 퍼진다.
     *
     * <p>서명은 이벤트 루프에서 동기 실행된다(RSA 2048 sign ≈ 수백 µs). 예산 초과가 관측되면 서명 전용
     * bounded scheduler 로 분리한다(계획 P2 (b)).
     */
    private ServerWebExchange withInternalToken(ServerWebExchange exchange, GatewayClaims claims) {
        String internalToken = internalTokenIssuer.issue(claims);
        ServerWebExchange mutated = exchange.mutate()
                .request(r -> r.headers(headers -> {
                    TRUSTED_HEADERS.forEach(headers::remove);
                    headers.remove(HttpHeaders.AUTHORIZATION);
                    headers.set(InternalTokenContract.HEADER, internalToken);
                }))
                .build();
        mutated.getAttributes().put(AUTHENTICATED_USER_ID_ATTR, String.valueOf(claims.userId()));
        return mutated;
    }

    /**
     * 거부 단일 지점 — S9 {@code auth.failure} 는 <b>여기서만</b> 증가한다(ADR-0024 D4).
     * 계측을 호출부에 흩으면 새 거부 경로가 생겼을 때 조용히 세어지지 않는다.
     *
     * <p>응답 헤더에는 {@link AuthFailureReason#wire()}(401 세부 사유는 합쳐진 값)를, 메트릭에는
     * {@link AuthFailureReason#tag()}(분해된 값)를 쓴다.
     */
    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, AuthFailureReason reason) {
        metrics.failed(reason);
        log.debug("gateway 거부: status={} reason={} path={}",
                status.value(), reason.tag(), exchange.getRequest().getPath());
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().add("X-Auth-Failure-Reason", reason.wire());
        return exchange.getResponse().setComplete();
    }
}
