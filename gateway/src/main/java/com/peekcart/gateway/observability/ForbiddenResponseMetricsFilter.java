package com.peekcart.gateway.observability;

import com.peekcart.gateway.auth.GatewayAuthenticationFilter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S9 인가 실패(403) 계측 — Gateway 소유 (ADR-0009 §Decision S9 · ADR-0024 D4).
 *
 * <p><b>왜 게이트웨이가 세는가</b>: 게이트웨이는 인가를 판정하지 않는다(ROLE_ADMIN 검사는 리소스를
 * 소유한 서비스 몫 — ADR-0017). 그래서 여기서 세는 403 은 전부 <b>다운스트림이 낸 판정</b>이다.
 * 서비스 5곳에 카운터를 복제하는 대안은 S9 의 "메트릭 이름은 owner 1개소" 계약을 깬다.
 *
 * <p><b>한계(의도)</b>: 게이트웨이를 거치지 않은 직접 호출의 403 은 세지 않는다. 그 경로는 애초에
 * NetworkPolicy 가 막는 축이고, 뚫렸다면 그것은 메트릭이 아니라 barrier smoke 가 잡을 사건이다.
 *
 * <p>order 는 인증 필터({@link GatewayAuthenticationFilter#AUTH_FILTER_ORDER})보다 <b>뒤</b>이되 라우트
 * 필터보다는 앞이라, 인증 필터가 스스로 종결한 401/503 은 이 필터를 통과하지 않는다(중복 집계 방지).
 */
@Component
public class ForbiddenResponseMetricsFilter implements GlobalFilter, Ordered {

    static final String AUTH_FORBIDDEN = "auth.forbidden";
    /** 라우트가 결정되지 않은 응답(라우트 미매칭 등) — 라우트별 태그를 비우지 않기 위한 고정값. */
    static final String UNROUTED = "unrouted";

    private final MeterRegistry registry;
    private final Map<String, Counter> forbiddenByRoute = new ConcurrentHashMap<>();

    public ForbiddenResponseMetricsFilter(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public int getOrder() {
        return GatewayAuthenticationFilter.AUTH_FILTER_ORDER + 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        return chain.filter(exchange).then(Mono.fromRunnable(() -> {
            if (exchange.getResponse().getStatusCode() == HttpStatus.FORBIDDEN) {
                forbidden(routeId(exchange)).increment();
            }
        }));
    }

    private static String routeId(ServerWebExchange exchange) {
        Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
        return route != null ? route.getId() : UNROUTED;
    }

    private Counter forbidden(String routeId) {
        return forbiddenByRoute.computeIfAbsent(routeId, id -> Counter.builder(AUTH_FORBIDDEN)
                .tag("route", id)
                .description("다운스트림이 거부한 요청 (403) — 인가 실패")
                .register(registry));
    }
}
