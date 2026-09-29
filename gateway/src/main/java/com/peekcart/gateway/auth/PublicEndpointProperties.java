package com.peekcart.gateway.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

import java.util.List;

/**
 * 공개 경로 allowlist — <b>method + path</b> SSOT.
 *
 * <p>여기 매칭되는 요청은 JWT 를 요구하지 않는다. 단 외부 유입 {@code X-User-*} 는
 * <b>공개 경로에서도 항상 제거</b>된다(spoofing 방지 — 필터가 담당).
 *
 * <p>method 까지 고정하는 이유: 서비스 측 matcher(`ProductSecurityConfig` 등)가 현재 method 무관
 * 문자열이라 `/api/v1/products/**` 전체가 permitAll 이다. Gateway 는 <b>GET 만</b> 공개로 좁혀
 * 관리형 POST/PUT/DELETE 가 무인증으로 새지 않게 한다.
 *
 * @param endpoints "METHOD /path/pattern" 형식 목록
 */
@ConfigurationProperties(prefix = "app.gateway.public")
public record PublicEndpointProperties(List<String> endpoints) {

    private static final PathPatternParser PARSER = PathPatternParser.defaultInstance;

    public List<Rule> rules() {
        return endpoints == null ? List.of() : endpoints.stream().map(Rule::parse).toList();
    }

    public record Rule(HttpMethod method, PathPattern pattern) {

        static Rule parse(String raw) {
            String[] parts = raw.trim().split("\\s+", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "공개 경로는 'METHOD /path' 형식이어야 합니다: " + raw);
            }
            return new Rule(HttpMethod.valueOf(parts[0].toUpperCase()), PARSER.parse(parts[1]));
        }

        public boolean matches(HttpMethod requestMethod, PathContainer path) {
            return method.equals(requestMethod) && pattern.matches(path);
        }
    }
}
