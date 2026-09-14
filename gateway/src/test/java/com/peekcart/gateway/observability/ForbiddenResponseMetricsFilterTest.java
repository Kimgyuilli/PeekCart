package com.peekcart.gateway.observability;

import com.peekcart.gateway.auth.GatewayAuthenticationFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * S9 인가 실패(403) 계측 회귀 — {@link ForbiddenResponseMetricsFilter}.
 *
 * <p>고정하는 계약: 다운스트림이 낸 403 만 센다 / 라우트별로 분리된다 / 200·401 은 세지 않는다 /
 * 인증 필터보다 뒤에서 돈다(게이트웨이 자신의 401·503 과 이중 집계되지 않는다).
 */
@DisplayName("ForbiddenResponseMetricsFilter — 403 계측")
class ForbiddenResponseMetricsFilterTest {

    private SimpleMeterRegistry registry;
    private ForbiddenResponseMetricsFilter filter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        filter = new ForbiddenResponseMetricsFilter(registry);
    }

    private static GatewayFilterChain respondWith(HttpStatus status) {
        return exchange -> {
            exchange.getResponse().setStatusCode(status);
            return Mono.empty();
        };
    }

    private static MockServerWebExchange requestOn(String routeId) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/admin/products/1"));
        if (routeId != null) {
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                    Route.async().id(routeId).uri(URI.create("http://upstream:8080"))
                            .predicate(e -> true).build());
        }
        return exchange;
    }

    private double count(String route) {
        return registry.get("auth.forbidden").tag("route", route).counter().count();
    }

    @Test
    @DisplayName("다운스트림 403 → auth.forbidden{route} +1")
    void forbidden_isCounted() {
        filter.filter(requestOn("product-admin"), respondWith(HttpStatus.FORBIDDEN)).block();

        assertThat(count("product-admin")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("200/401 은 세지 않는다 (인증 실패는 auth.failure 소관)")
    void nonForbiddenStatuses_areNotCounted() {
        filter.filter(requestOn("product-admin"), respondWith(HttpStatus.OK)).block();
        filter.filter(requestOn("product-admin"), respondWith(HttpStatus.UNAUTHORIZED)).block();

        assertThat(registry.find("auth.forbidden").counters()).isEmpty();
    }

    @Test
    @DisplayName("라우트별로 분리된다 — 어느 리소스가 거부했는지 보여야 한다")
    void countersAreSeparatedByRoute() {
        filter.filter(requestOn("product-admin"), respondWith(HttpStatus.FORBIDDEN)).block();
        filter.filter(requestOn("order-deadletter-admin-summary"), respondWith(HttpStatus.FORBIDDEN)).block();

        assertThat(count("product-admin")).isEqualTo(1.0);
        assertThat(count("order-deadletter-admin-summary")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("라우트 미결정 403 → route=unrouted (태그가 비지 않는다)")
    void unroutedForbidden_usesFixedTag() {
        filter.filter(requestOn(null), respondWith(HttpStatus.FORBIDDEN)).block();

        assertThat(count(ForbiddenResponseMetricsFilter.UNROUTED)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("인증 필터보다 뒤에서 돈다 — 게이트웨이가 스스로 끝낸 401/503 은 이 필터에 오지 않는다")
    void ordersAfterAuthenticationFilter() {
        assertThat(filter.getOrder())
                .isGreaterThan(GatewayAuthenticationFilter.AUTH_FILTER_ORDER);
    }
}
