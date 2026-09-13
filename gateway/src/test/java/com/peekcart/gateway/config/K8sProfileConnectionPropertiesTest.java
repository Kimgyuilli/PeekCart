package com.peekcart.gateway.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.source.ConfigurationProperty;
import org.springframework.boot.context.properties.source.ConfigurationPropertyName;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * k8s 프로파일의 <b>연결 정보 소유권</b>을 고정한다 (ADR-0007 · 구현 ③ PR3b P25).
 *
 * <p>왜 필요한가: 기본 테스트는 {@code application-k8s.yml} 을 로드하지 않는다(CI 는 오히려
 * {@code SPRING_PROFILES_ACTIVE=test}). 그래서 프로파일 키가 오타이거나 파일째 비활성이어도
 * base 기본값이 같은 값을 내주어 <b>테스트가 그대로 통과</b>한다 — 값만 비교하면 무의미하다.
 *
 * <p>따라서 값이 아니라 <b>그 값이 어디서 왔는지(origin)</b>를 검증한다: 각 연결 키가
 * {@code application-k8s.yml} property source 에서 해석돼야 한다. 클러스터에서 ConfigMap 배선이
 * 빠지면(SPRING_PROFILES_ACTIVE 미주입) Redis 가 localhost 로 붙는 사고가 이 계약의 반대편이고,
 * 그쪽은 {@code scripts/gateway-exposure-lint.sh} 가 렌더 산출에서 막는다.
 */
@SpringBootTest
@ActiveProfiles("k8s")
@TestPropertySource(properties = {
        // 내부 토큰 개인키는 산출물에 없다(ADR-0013 D2) — 테스트는 공유 fixture 키를 마운트한다.
        "app.gateway.internal-token.active-kid=gw-test-2026",
        "app.gateway.internal-token.private-key-location=classpath:internal-token/gateway-test-private.pem",
        // 부팅 시 JWKS 폴링으로 테스트가 느려지지 않게 — 연결값 검증과 무관
        "app.gateway.jwt.jwks-initial-delay=PT1H",
        "app.gateway.jwt.jwks-refresh-interval=PT1H"
})
@DisplayName("k8s 프로파일 — 연결 정보 소유권(ADR-0007)")
class K8sProfileConnectionPropertiesTest {

    private static final String K8S_SOURCE_MARKER = "application-k8s.yml";

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @ParameterizedTest(name = "{0} = {1} (origin: application-k8s.yml)")
    @CsvSource({
            "spring.data.redis.host,                    redis",
            "spring.data.redis.port,                    6379",
            "app.gateway.upstream.user-uri,             http://user-service:8080",
            "app.gateway.upstream.product-uri,          http://product-service:8080",
            "app.gateway.upstream.order-uri,            http://order-service:8080",
            "app.gateway.upstream.payment-uri,          http://payment-service:8080",
            "app.gateway.upstream.notification-uri,     http://notification-service:8080",
            "app.gateway.jwt.jwks-uri,                  http://user-service:8080/.well-known/jwks.json"
    })
    @DisplayName("연결 키가 k8s 프로파일에서 해석된다 — base 기본값이 대신 서 있으면 실패")
    void connectionPropertyIsOwnedByK8sProfile(String key, String expectedValue) {
        ConfigurationPropertyName name = ConfigurationPropertyName.of(key);
        ConfigurationProperty property = null;
        for (ConfigurationPropertySource source : ConfigurationPropertySources.get(environment)) {
            ConfigurationProperty candidate = source.getConfigurationProperty(name);
            if (candidate != null) {
                property = candidate;
                break;
            }
        }

        assertThat(property)
                .as("%s 가 어떤 property source 에도 없다 — 키 오타 가능성", key)
                .isNotNull();
        assertThat(String.valueOf(property.getValue())).isEqualTo(expectedValue);
        assertThat(String.valueOf(property.getOrigin()))
                .as("%s 는 application-k8s.yml 이 소유해야 한다(ADR-0007). 다른 origin 이면 "
                        + "프로파일이 비활성이거나 키 이름이 어긋난 것 — 값이 같아도 계약 위반", key)
                .contains(K8S_SOURCE_MARKER);
    }

    @Test
    @DisplayName("k8s 프로파일에서 17개 라우트 uri 가 모두 해석된다")
    void routeUrisResolveUnderK8sProfile() {
        Map<String, String> uriByRouteId = routeUris();

        // 업무 9 + DLQ 관리자 라우트 8(4서비스 × 요약/전이, ④-c-2b-4b P27).
        // **개수를 고정한다** — 라우트를 더하면서 이 테스트를 지나치면 신규 라우트의 placeholder 해석이
        // 아무 데서도 검사되지 않는다.
        assertThat(uriByRouteId).hasSize(17);
        assertThat(uriByRouteId).allSatisfy((id, uri) ->
                assertThat(uri).as("라우트 %s 의 uri 가 미해석 placeholder", id).doesNotContain("${"));
    }

    private Map<String, String> routeUris() {
        return routeDefinitionLocator.getRouteDefinitions()
                .collectList()
                .block()
                .stream()
                .collect(Collectors.toMap(RouteDefinition::getId, r -> r.getUri().toString()));
    }

    /**
     * placeholder <b>연결</b>의 회귀를 잡는다 — 위의 origin 검사와 목적이 다르다.
     *
     * <p>프로파일 값과 base 기본값이 같은 문자열이라, 어떤 라우트가 옛 이름
     * ({@code ${USER_SERVICE_URI:...}})으로 되돌아가도 uri 값은 그대로 http://user-service:8080 이다
     * — 값 비교로는 절대 잡히지 않는다. 그래서 여기서는 upstream 키를 <b>식별 가능한 다른 값</b>으로
     * override 하고 17개 라우트 전부가 그 키를 따라가는지 본다.
     */
    @Nested
    @SpringBootTest
    @ActiveProfiles("k8s")
    @TestPropertySource(properties = {
            "app.gateway.jwt.jwks-initial-delay=PT1H",
            "app.gateway.jwt.jwks-refresh-interval=PT1H",
            "app.gateway.upstream.user-uri=http://sentinel-user:9",
            "app.gateway.upstream.product-uri=http://sentinel-product:9",
            "app.gateway.upstream.order-uri=http://sentinel-order:9",
            "app.gateway.upstream.payment-uri=http://sentinel-payment:9",
            "app.gateway.upstream.notification-uri=http://sentinel-notification:9"
    })
    @DisplayName("라우트가 upstream 키를 실제로 따라간다 (placeholder 연결 회귀)")
    class UpstreamPlaceholderWiringTest {

        @Autowired
        private RouteDefinitionLocator locator;

        @Test
        @DisplayName("17개 라우트 전부가 override 한 upstream 값으로 해석된다")
        void everyRouteFollowsUpstreamKey() {
            Map<String, String> uriByRouteId = locator.getRouteDefinitions()
                    .collectList()
                    .block()
                    .stream()
                    .collect(Collectors.toMap(RouteDefinition::getId, r -> r.getUri().toString()));

            assertThat(uriByRouteId)
                    .containsEntry("user-auth-preauth", "http://sentinel-user:9")
                    .containsEntry("user-auth", "http://sentinel-user:9")
                    .containsEntry("user-users", "http://sentinel-user:9")
                    .containsEntry("product-admin", "http://sentinel-product:9")
                    .containsEntry("product-catalog", "http://sentinel-product:9")
                    .containsEntry("order-cart", "http://sentinel-order:9")
                    .containsEntry("order-orders", "http://sentinel-order:9")
                    .containsEntry("payment", "http://sentinel-payment:9")
                    .containsEntry("notification", "http://sentinel-notification:9")
                    // DLQ 관리자 라우트도 같은 upstream 키를 따른다 (④-c-2b-4b P27).
                    // 여기가 sentinel 을 따르지 않으면 라우트가 옛 placeholder 이름으로 되돌아간 것이다.
                    .containsEntry("order-deadletter-admin-summary", "http://sentinel-order:9")
                    .containsEntry("order-deadletter-admin-transition", "http://sentinel-order:9")
                    .containsEntry("product-deadletter-admin-summary", "http://sentinel-product:9")
                    .containsEntry("product-deadletter-admin-transition", "http://sentinel-product:9")
                    .containsEntry("payment-deadletter-admin-summary", "http://sentinel-payment:9")
                    .containsEntry("payment-deadletter-admin-transition", "http://sentinel-payment:9")
                    .containsEntry("notification-deadletter-admin-summary", "http://sentinel-notification:9")
                    .containsEntry("notification-deadletter-admin-transition", "http://sentinel-notification:9");
        }
    }
}
