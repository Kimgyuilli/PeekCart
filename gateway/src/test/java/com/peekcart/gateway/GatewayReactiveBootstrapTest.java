package com.peekcart.gateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.reactive.context.ReactiveWebApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gateway 가 <b>WebFlux(reactive)</b> 로 부팅하고 servlet 스택이 유입되지 않았음을 고정한다
 * (PLAN-BLINDSPOTS B6 — `common/build.gradle` 이 starter-web 을 `api` 로 전이 노출).
 *
 * <p>Gradle 가드({@code assertGatewayHasNoServletDeps})가 의존 선언을, 이 테스트가 <b>런타임 부팅</b>을
 * 각각 막는다. 둘 중 하나만으로는 부족하다 — 가드는 전이 경로를 놓칠 수 있고, 부팅 테스트만으로는
 * 왜 깨졌는지 진단이 어렵다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        // JWKS/업스트림은 부팅에 필요 없다(최초 fetch 는 lazy/scheduled) — 주기 갱신만 늦춰 소음 제거
        "app.gateway.jwt.jwks-uri=http://localhost:1/.well-known/jwks.json",
        // 내부 토큰 개인키는 산출물에 없다(ADR-0013 D2) — 테스트는 공유 fixture 키를 마운트한다.
        "app.gateway.internal-token.active-kid=gw-test-2026",
        "app.gateway.internal-token.private-key-location=classpath:internal-token/gateway-test-private.pem",
        "app.gateway.jwt.jwks-initial-delay=PT1H",
        "app.gateway.jwt.jwks-refresh-interval=PT1H"
})
@DisplayName("gateway 부팅 — WebFlux 전용 · servlet 부재")
class GatewayReactiveBootstrapTest {

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("ApplicationContext 가 reactive 다 (servlet 이면 MVC 로 부팅돼 gateway 가 죽는다)")
    void contextIsReactive() {
        assertThat(context).isInstanceOf(ReactiveWebApplicationContext.class);
    }

    @ParameterizedTest(name = "servlet 마커 클래스 부재: {0}")
    @ValueSource(strings = {
            "jakarta.servlet.Servlet",
            "org.springframework.web.servlet.DispatcherServlet",
            "org.apache.catalina.startup.Tomcat"
    })
    @DisplayName("servlet/MVC 클래스가 classpath 에 없다")
    void servletClassesAbsent(String fqcn) {
        assertThat(isPresent(fqcn))
                .as("%s 가 classpath 에 있으면 :common 등으로 servlet 스택이 유입된 것", fqcn)
                .isFalse();
    }

    @Test
    @DisplayName("reactive 마커 클래스는 존재한다 (음성 테스트의 vacuous-green 차단)")
    void reactiveClassesPresent() {
        assertThat(isPresent("org.springframework.web.reactive.DispatcherHandler")).isTrue();
        assertThat(isPresent("org.springframework.cloud.gateway.filter.GlobalFilter")).isTrue();
    }

    private static boolean isPresent(String fqcn) {
        try {
            Class.forName(fqcn, false, GatewayReactiveBootstrapTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
