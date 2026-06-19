package com.peekcart.payment.infrastructure.security;

import com.peekcart.global.config.ActuatorSecurityConfig;
import com.peekcart.global.security.JwtSecurityConfigurer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Payment 서비스의 thin Spring Security 설정 (ADR-0014 D1 · ADR-0011 §D2).
 * <p>공통 JWT 검증 정책은 common-auth {@link JwtSecurityConfigurer} 로 위임하고,
 * 본 모듈은 {@code SecurityFilterChain} 빈을 <b>정확히 1개</b> 생성한다.
 * <p>{@link EnableMethodSecurity} 는 메서드 보안(@PreAuthorize) 구동용. Payment 는 발급자가 아니라 검증 전용이다.
 * <p>Toss 웹훅(POST /api/v1/payments/webhook)은 외부 콜백이라 공개, 그 외 결제 API 는 인증 필요.
 * actuator permitAll 은 {@link ActuatorSecurityConfig}(ADR-0009 S4 단일 소유)에서 합친다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class PaymentSecurityConfig {

    private final JwtSecurityConfigurer jwtSecurityConfigurer;

    /** 서비스별 공개 API — Toss 웹훅(외부 콜백)만 공개. actuator 는 ActuatorSecurityConfig 가 합친다. */
    private static final String[] BUSINESS_PUBLIC_URLS = {
            "/api/v1/payments/webhook",
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
