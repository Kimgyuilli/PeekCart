package com.peekcart.gateway.route;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DLQ 원장 <b>관리자 라우트</b> 계약 (구현 ④-c-2b-4b P27 · ADR-0022 §D5 · 계획 V-40).
 *
 * <p><b>왜 이 테스트가 필요한가</b>: ④-c-2b-4a 의 replay 진입점은 ADMIN 가드가 정확했지만
 * <b>그 가드에 도달할 경로가 없었다</b>(§10 R9) — 라우트가 `/api/v1/**` 뿐이고 리소스 서비스는
 * 게이트웨이 서명 토큰에서만 주체를 세우므로 직접 호출은 인증에서 끊긴다. 이 라우트가 그 경로다.
 *
 * <p><b>무엇을 관측하는가</b>: YAML 을 읽는 것이 아니라 <b>실제 {@link RouteLocator} 가 만든 predicate 를
 * 요청에 대해 평가</b>하고, {@code RewritePath} 필터를 <b>실행해</b> 재작성 결과 경로를 본다.
 * "설정에 적혀 있다" 는 관측이 아니다.
 *
 * <p><b>핵심 음성 검사</b>: 이 라우트는 외부 경로를 actuator 에 잇는다. id 를 숫자로 못박지 않으면
 * 그 순간 actuator 전면 노출이 된다 — traversal·인코딩 변형이 <b>매칭 자체에 실패</b>하는 것을 고정한다.
 * predicate 를 `/**` 로 넓히면 이 검사들이 red 가 된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "app.gateway.jwt.jwks-uri=http://localhost:1/.well-known/jwks.json",
        "app.gateway.internal-token.active-kid=gw-test-2026",
        "app.gateway.internal-token.private-key-location=classpath:internal-token/gateway-test-private.pem",
        "app.gateway.jwt.jwks-initial-delay=PT1H",
        "app.gateway.jwt.jwks-refresh-interval=PT1H"
})
@DisplayName("DLQ 관리자 라우트 — 도달 경로 · actuator 누출 차단 (P27)")
class DeadLetterAdminRouteContractTest {

    @Autowired
    private RouteLocator routeLocator;

    @ParameterizedTest(name = "{0} 의 전이 라우트가 존재하고 숫자 id 를 받는다")
    @ValueSource(strings = {"order", "product", "payment", "notification"})
    @DisplayName("4개 도메인 서비스 전부에 전이 라우트가 있다")
    void transitionRouteExistsForEachService(String service) {
        Route route = findRoute(service + "-deadletter-admin-transition");

        assertThat(matches(route, "/api/v1/admin/deadletter/" + service + "/42")).isTrue();
        assertThat(rewrite(route, "/api/v1/admin/deadletter/" + service + "/42"))
                .isEqualTo("/actuator/deadletter/42");
    }

    @ParameterizedTest(name = "{0} 의 backlog 조회 라우트")
    @ValueSource(strings = {"order", "product", "payment", "notification"})
    @DisplayName("backlog 조회 라우트가 /actuator/deadletter 로 재작성된다")
    void summaryRouteExistsForEachService(String service) {
        Route route = findRoute(service + "-deadletter-admin-summary");

        assertThat(matches(route, "/api/v1/admin/deadletter/" + service)).isTrue();
        assertThat(rewrite(route, "/api/v1/admin/deadletter/" + service)).isEqualTo("/actuator/deadletter");
    }

    /**
     * <b>이 테스트가 이 라우트의 위험 통제 전부다.</b> predicate 를 `/**` 로 넓히거나 id 제약을 빼면
     * 아래 입력들이 매칭되고, rewrite 결과가 actuator 의 다른 엔드포인트를 가리킨다.
     */
    @ParameterizedTest(name = "actuator 누출 시도 차단: {0}")
    @ValueSource(strings = {
            "/api/v1/admin/deadletter/order/env",
            "/api/v1/admin/deadletter/order/../env",
            "/api/v1/admin/deadletter/order/%2e%2e/env",
            "/api/v1/admin/deadletter/order/42/../../env",
            "/api/v1/admin/deadletter/order/beans",
            "/api/v1/admin/deadletter/order/42abc"
    })
    @DisplayName("숫자가 아닌 segment 는 어떤 deadletter 라우트에도 매칭되지 않는다")
    void nonNumericSegmentsNeverMatch(String path) {
        List<Route> deadLetterRoutes = routeLocator.getRoutes()
                .filter(route -> route.getId().contains("deadletter"))
                .collectList()
                .block();

        assertThat(deadLetterRoutes).isNotEmpty();
        assertThat(deadLetterRoutes)
                .as("%s 가 매칭되면 재작성 결과가 actuator 의 다른 엔드포인트를 가리킨다", path)
                .noneMatch(route -> matches(route, path));
    }

    @Test
    @DisplayName("라우트가 앱 포트(8080) 업스트림을 가리킨다 — 관리 포트가 아니다")
    void routesTargetApplicationPort() {
        // 도메인 서비스는 management.server.port 를 분리하지 않는다(gateway 만 8081).
        // 여기가 8081 로 바뀌면 존재하지 않는 포트로 프록시된다.
        assertThat(findRoute("order-deadletter-admin-transition").getUri().toString())
                .isEqualTo("http://order-service:8080");
    }

    private Route findRoute(String id) {
        Optional<Route> route = routeLocator.getRoutes()
                .filter(candidate -> id.equals(candidate.getId()))
                .next()
                .blockOptional();
        assertThat(route).as("라우트 %s 가 없다", id).isPresent();
        return route.get();
    }

    /**
     * <b>{@link Route#getPredicate()} 는 {@code AsyncPredicate} 라 {@code apply} 가
     * {@code Publisher<Boolean>} 을 돌려준다.</b> 이것을 Boolean 과 직접 비교하면 <b>언제나 false</b> 이고,
     * 그러면 아래 음성 검사들이 "매칭 안 됨" 을 이유로 <b>전부 vacuous-green</b> 이 된다
     * (이 테스트를 처음 돌렸을 때 실제로 그랬다). 반드시 구독해서 값을 꺼낸다.
     */
    private boolean matches(Route route, String path) {
        return Boolean.TRUE.equals(Mono.from(route.getPredicate().apply(exchangeFor(path))).block());
    }

    /** {@code RewritePath} 필터를 <b>실행해</b> 재작성된 경로를 얻는다. */
    private String rewrite(Route route, String path) {
        AtomicReference<String> rewritten = new AtomicReference<>(path);
        GatewayFilterChain capturing = mutated -> {
            rewritten.set(mutated.getRequest().getURI().getRawPath());
            return Mono.empty();
        };
        // RewritePath 만 실행한다 — 같은 라우트의 RequestRateLimiter 를 함께 돌리면 Redis 에 붙는다.
        for (GatewayFilter filter : route.getFilters()) {
            if (!filter.toString().contains("RewritePath")) {
                continue;
            }
            ServerWebExchange current = exchangeFor(rewritten.get());
            filter.filter(current, capturing).block();
        }
        return rewritten.get();
    }

    private static MockServerWebExchange exchangeFor(String path) {
        return MockServerWebExchange.from(MockServerHttpRequest.post(path).build());
    }
}
