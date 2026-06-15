package com.peekcart.notification.infrastructure.security;

import com.peekcart.global.config.ActuatorSecurityConfig;
import com.peekcart.global.security.JwtSecurityConfigurer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Notification 서비스의 thin Spring Security 설정 (ADR-0014 D1 · ADR-0011 §D2).
 * <p>공통 JWT 검증 정책은 common-auth {@link JwtSecurityConfigurer} 로 위임하고,
 * 본 모듈은 {@code SecurityFilterChain} 빈을 <b>정확히 1개</b> 생성한다.
 * <p>PUBLIC_URLS 는 서비스별 공개 API(swagger)만 선언하며, actuator permitAll 은
 * {@link ActuatorSecurityConfig}(ADR-0009 S4 단일 소유)에서 합친다 — 서비스에 actuator 경로 재기재 금지.
 * 알림 조회 API(/api/v1/notifications)는 인증 필요.
 */
@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class NotificationSecurityConfig {

    private final JwtSecurityConfigurer jwtSecurityConfigurer;

    /** 서비스별 공개 API — actuator 는 ActuatorSecurityConfig 가 합친다. */
    private static final String[] BUSINESS_PUBLIC_URLS = {
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api-docs/**"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        jwtSecurityConfigurer.apply(http, ActuatorSecurityConfig.mergedPublicUrls(BUSINESS_PUBLIC_URLS));
        return http.build();
    }
}
