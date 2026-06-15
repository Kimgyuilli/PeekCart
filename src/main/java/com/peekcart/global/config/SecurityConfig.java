package com.peekcart.global.config;

import com.peekcart.global.security.JwtSecurityConfigurer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 전환기 root app(User/Product/Order/Payment 잔류)의 Spring Security 설정.
 * 공통 JWT 정책은 common-auth {@link JwtSecurityConfigurer}로 위임하고,
 * 본 모듈은 {@code SecurityFilterChain} 빈을 정확히 1개 생성한다 (ADR-0014 D1).
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtSecurityConfigurer jwtSecurityConfigurer;

    // 비즈니스 공개 URL — actuator permitAll 은 ActuatorSecurityConfig(ADR-0009 S4 단일 소유)에서 합친다.
    // [PR2b] /api/v1/auth/** 는 User 서비스로 peel 됨 — root 잔여(Product/Order/Payment)만 선언.
    private static final String[] BUSINESS_PUBLIC_URLS = {
            "/api/v1/products",
            "/api/v1/products/**",
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
