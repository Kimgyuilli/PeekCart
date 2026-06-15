package com.peekcart.user.infrastructure.security;

import com.peekcart.global.config.ActuatorSecurityConfig;
import com.peekcart.global.security.JwtSecurityConfigurer;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * User 서비스의 thin Spring Security 설정 (ADR-0014 D1 · ADR-0011 §D2).
 * <p>공통 JWT 검증 정책은 common-auth {@link JwtSecurityConfigurer} 로 위임하고,
 * 본 모듈은 {@code SecurityFilterChain} 빈을 <b>정확히 1개</b> 생성한다.
 * <p>PUBLIC_URLS 는 서비스별 공개 API(회원가입/로그인/토큰 재발급 + swagger)만 선언하며,
 * actuator permitAll 은 {@link ActuatorSecurityConfig}(ADR-0009 S4 단일 소유)에서 합친다
 * — 서비스에 actuator 경로 재기재 금지. 로그아웃·회원 조회 API 는 인증 필요.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class UserSecurityConfig {

    private final JwtSecurityConfigurer jwtSecurityConfigurer;

    /** 서비스별 공개 API — actuator 는 ActuatorSecurityConfig 가 합친다. */
    private static final String[] BUSINESS_PUBLIC_URLS = {
            "/api/v1/auth/signup",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/api-docs/**"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        jwtSecurityConfigurer.apply(http, ActuatorSecurityConfig.mergedPublicUrls(BUSINESS_PUBLIC_URLS));
        return http.build();
    }

    /** BCrypt 기반 패스워드 인코더 (User 발급 전속 — U4/GP-2 P0 #1, root 에서 이관). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
