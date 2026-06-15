package com.peekcart.global.config;

import java.util.stream.Stream;

/**
 * actuator 보안 허용 경로의 단일 소유처 (ADR-0009 §Decision S4).
 * <p>{@code /actuator/health/**}·{@code /actuator/prometheus} permitAll 목록을 <b>1개소</b>로 고정한다.
 * 각 서비스(및 전환기 root app)는 자기 {@code SecurityFilterChain} 을 만들 때
 * {@link #mergedPublicUrls(String...)} 로 자기 비즈니스 PUBLIC_URLS 에 본 경로를 합친다 —
 * 서비스별 PUBLIC_URLS 에 actuator 경로를 직접 재기재하지 않는다(과허용 회귀/드리프트 차단).
 * <p>scrape/Probe 의존: K8s liveness/readiness, Prometheus scrape (no-auth 200).
 */
public final class ActuatorSecurityConfig {

    /** actuator permitAll 경로 — 본 배열이 유일 정의처. 외부는 {@link #mergedPublicUrls(String...)} 로만 접근. */
    private static final String[] PUBLIC_PATHS = {
            "/actuator/health/**",
            "/actuator/prometheus"
    };

    private ActuatorSecurityConfig() {
    }

    /**
     * 서비스 비즈니스 PUBLIC_URLS 에 공통 actuator permitAll 경로를 합쳐 반환한다.
     *
     * @param businessPublicUrls 서비스별 공개 API(예: swagger, 비인증 비즈니스 endpoint)
     * @return 비즈니스 + actuator 경로 병합 배열
     */
    public static String[] mergedPublicUrls(String... businessPublicUrls) {
        return Stream.concat(Stream.of(businessPublicUrls), Stream.of(PUBLIC_PATHS))
                .toArray(String[]::new);
    }
}
