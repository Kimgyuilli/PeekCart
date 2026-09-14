package com.peekcart.gateway.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * gateway 관측성 계약 회귀 — S1(histogram bucket) · S2(application 태그) · S9(auth.failure)
 * (ADR-0009 §Decision S1/S2/S9 · ADR-0024 D1/D2/D4).
 *
 * <p><b>왜 필요한가</b>: gateway 는 도메인 5서비스와 달리 {@code :common} 을 의존하지 않아 관측성
 * 배선이 따로다. 셋 중 하나라도 빠지면 증상이 서로 다르게 나타난다 —
 * S1 부재면 p95 alert 가 NaN, S2 부재면 alert regex 가 gateway 를 못 잡고, S9 부재면 거부 사유를
 * 볼 수 없다. 그런데 셋 다 <b>애플리케이션은 정상 동작</b>하므로 다른 테스트로는 드러나지 않는다.
 *
 * <p>실제 스크랩 경로(관리 포트 8081의 {@code /actuator/prometheus})를 그대로 친다 —
 * MeterRegistry 를 직접 들여다보면 노출 설정이 깨져도 통과한다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 테스트에서는 메트릭 export 자동설정이 기본 비활성이라 PrometheusMeterRegistry 가 안 만들어진다
// (5서비스의 ObservabilityMetricsIntegrationTest 와 같은 이유, ADR-0009 P1-D).
@AutoConfigureObservability
@TestPropertySource(properties = {
        // 관리 포트 분리(base yml)를 유지한 채 랜덤 포트로 띄운다 — 포트 분리 자체가 계약이다.
        "management.server.port=0",
        "app.gateway.jwt.jwks-uri=http://localhost:1/.well-known/jwks.json",
        "app.gateway.internal-token.active-kid=gw-test-2026",
        "app.gateway.internal-token.private-key-location=classpath:internal-token/gateway-test-private.pem",
        "app.gateway.jwt.jwks-initial-delay=PT1H",
        "app.gateway.jwt.jwks-refresh-interval=PT1H"
})
@DisplayName("gateway 관측성 — S1 bucket · S2 application 태그 · S9 auth.failure")
class GatewayObservabilityMetricsIntegrationTest {

    @LocalServerPort
    private int appPort;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private WebClient.Builder webClientBuilder;

    /** 보호 경로를 무토큰으로 친다 — 업스트림에 닿지 않고 게이트웨이가 401 로 끊는 결정적 경로다. */
    private void rejectedRequest() {
        HttpStatus status = webClientBuilder.build().get()
                .uri("http://localhost:" + appPort + "/api/v1/orders")
                .exchangeToMono(r -> reactor.core.publisher.Mono.just(HttpStatus.valueOf(r.statusCode().value())))
                .block(Duration.ofSeconds(10));
        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String scrape() {
        return webClientBuilder.build().get()
                .uri("http://localhost:" + managementPort + "/actuator/prometheus")
                .retrieve()
                .bodyToMono(String.class)
                .block(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("S2 — 모든 시계열에 application=\"gateway\" 태그가 붙는다")
    void applicationTagIsPresent() {
        String body = scrape();

        assertThat(body)
                .as("태그가 없으면 alert 의 application=~ regex 가 gateway 를 영원히 못 잡는다")
                .contains("application=\"gateway\"");
    }

    @Test
    @DisplayName("S1 — http.server.requests 에 histogram bucket 이 노출된다 (p95 NaN 방지)")
    void histogramBucketsAreExposed() {
        rejectedRequest();

        String body = scrape();

        assertThat(body)
                .as("공유 MetricsConfig(:peekcart-common-observability) 가 배선되지 않으면 bucket 이 없다")
                .contains("http_server_requests_seconds_bucket");
        assertThat(body).contains("le=");
    }

    @Test
    @DisplayName("S9 — 거부 사유가 스크랩 출력에 reason 태그로 나온다 (registry 가 아니라 노출 경로로 확인)")
    void authFailureIsScrapable() {
        rejectedRequest();

        String body = scrape();

        assertThat(body).contains("auth_failure_total");
        assertThat(body)
                .as("사유가 태그로 안 나오면 '거부가 늘었다' 까지만 알고 왜인지는 로그를 다시 읽어야 한다")
                .contains("reason=\"missing_token\"");
    }

    @Test
    @DisplayName("관리 포트와 앱 포트는 분리돼 있고, 앱 포트로는 actuator 에 닿지 않는다")
    void actuatorIsNotOnApplicationPort() {
        assertThat(managementPort).isNotEqualTo(appPort);

        HttpStatus status = webClientBuilder.build().get()
                .uri("http://localhost:" + appPort + "/actuator/prometheus")
                .exchangeToMono(r -> reactor.core.publisher.Mono.just(HttpStatus.valueOf(r.statusCode().value())))
                .onErrorResume(WebClientResponseException.class,
                        e -> reactor.core.publisher.Mono.just(HttpStatus.valueOf(e.getStatusCode().value())))
                .block(Duration.ofSeconds(10));

        assertThat(status)
                .as("외부 진입점(8080)에 메트릭이 노출되면 라우트가 없어도 직접 접근된다")
                .isNotEqualTo(HttpStatus.OK);
    }
}
