package com.peekcart.global.security;

import com.peekcart.global.auth.TokenBlacklistLookupPort;
import com.peekcart.global.filter.MdcFilter;
import com.peekcart.global.jwt.JwtFilter;
import com.peekcart.global.jwt.JwtTokenVerifier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;

/**
 * 재사용 가능한 JWT 보안 설정 기여 (ADR-0014 D1).
 * 각 서비스(및 전환기 root app)는 자기 모듈에서 {@code SecurityFilterChain} 빈을 <b>정확히 1개</b>
 * 생성하고, 자기 PUBLIC_URLS 만 넘겨 본 configurer 로 공통 정책(stateless·csrf off·JwtFilter·handler)을
 * 적용한다. {@link JwtFilter}/handler 정의는 common-auth 1곳에만 존재한다.
 */
@Component
@RequiredArgsConstructor
public class JwtSecurityConfigurer {

    private final JwtTokenVerifier jwtTokenVerifier;
    private final TokenBlacklistLookupPort tokenBlacklistLookupPort;
    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;

    /**
     * 공통 JWT 보안 정책을 {@code http} 에 적용한다. 호출자가 {@code http.build()} 로 단일 체인을 완성한다.
     *
     * @param http       서비스의 SecurityFilterChain 빌더
     * @param publicUrls 해당 서비스의 인증 면제 URL
     */
    public void apply(HttpSecurity http, String[] publicUrls) throws Exception {
        JwtFilter jwtFilter = new JwtFilter(jwtTokenVerifier, tokenBlacklistLookupPort);

        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(publicUrls).permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new MdcFilter(), JwtFilter.class);
    }
}
