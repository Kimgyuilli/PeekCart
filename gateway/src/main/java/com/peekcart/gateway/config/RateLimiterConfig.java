package com.peekcart.gateway.config;

import com.peekcart.gateway.auth.GatewayAuthenticationFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.server.reactive.ServerHttpRequest;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

/**
 * RequestRateLimiter 의 route-class 별 키 해석기 (ADR-0013 D3).
 *
 * <ul>
 *   <li><b>인증 후</b> — {@link #userKeyResolver()}: 인증 필터가 검증 후 exchange attribute 에 넣은
 *       userId <b>만</b> 사용한다. 요청 헤더의 {@code X-User-Id} 를 직접 읽으면 위조 값으로 버킷을
 *       무한 생성할 수 있다.</li>
 *   <li><b>인증 전</b>(signup/login/refresh) — {@link #preAuthKeyResolver()}: <b>IP</b> 기준.</li>
 *   <li><b>공개 조회</b> — {@link #ipKeyResolver()}: IP 기준.</li>
 * </ul>
 *
 * <p><b>계정 차원 제한은 없다</b>: 계정 성분은 login/signup
 * 이 email 을 JSON 본문으로 받기 때문에 gateway 가 body-caching decorator 를 도입해야 얻을 수 있다.
 * {@code ?email} 쿼리는 실제 인증 대상과 무관해 공격자가 쿼리만 바꿔 버킷을 회피할 수 있으므로
 * 쓰지 않는다. 키는 <b>IP 단독</b>이다.
 */
@Configuration
public class RateLimiterConfig {

    private static final String ANONYMOUS = "anonymous";

    /**
     * 기본 KeyResolver — {@code RequestRateLimiterGatewayFilterFactory} 가 단일 기본 빈을 요구하므로
     * {@link Primary} 로 지정한다. 라우트는 모두 key-resolver 를 명시하지만, 누락 시 가장 보수적인
     * IP 기준으로 떨어지게 한다.
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just("ip:" + clientIp(exchange.getRequest()));
    }

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            Object userId = exchange.getAttribute(GatewayAuthenticationFilter.AUTHENTICATED_USER_ID_ATTR);
            // 인증 필터가 먼저 실행되므로 보호 라우트에는 attribute 가 있다. 없으면(공개 라우트에
            // 잘못 붙였거나 무토큰 통과) IP 로 강등한다 — 키 부재로 무제한이 되지 않게.
            return Mono.just(userId instanceof String s && !s.isBlank()
                    ? "user:" + s
                    : "ip:" + clientIp(exchange.getRequest()));
        };
    }

    @Bean
    public KeyResolver preAuthKeyResolver() {
        return exchange -> Mono.just("preauth:" + clientIp(exchange.getRequest()));
    }

    private static String clientIp(ServerHttpRequest request) {
        // 프록시 체인 앞단(LB)이 세팅하는 X-Forwarded-For 우선. 스푸핑 방지는 LB 가 덮어쓰는 전제.
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        InetSocketAddress remote = request.getRemoteAddress();
        return remote != null && remote.getAddress() != null
                ? remote.getAddress().getHostAddress()
                : ANONYMOUS;
    }
}
