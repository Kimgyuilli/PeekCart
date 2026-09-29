package com.peekcart.global.security;

import com.peekcart.global.filter.MdcFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.stereotype.Component;

/**
 * Gateway 신뢰 기반 보안 설정 기여(ADR-0013 D3 · ADR-0017). 인증 필터는 {@link InternalTokenAuthenticationFilter}
 * (Gateway 서명 내부 토큰)이고, csrf off·STATELESS·예외 핸들러·MdcFilter 순서 등 공통 정책을 함께 고정한다
 * — 인증 필터 외 정책이 바뀌면 401/403·MDC 계약이 조용히 달라진다.
 *
 * <p>각 서비스는 자기 모듈에서 {@code SecurityFilterChain} 빈을 정확히 1개 생성하고 자기 PUBLIC_URLS 만 넘긴다.
 * 사용자 토큰은 더 이상 서비스에서 검증하지 않는다(ADR-0014 D2-c exit) — 서명 검증 대상은 Gateway 내부 토큰뿐이다.
 */
@Component
@RequiredArgsConstructor
public class HeaderTrustSecurityConfigurer {

    private final JwtAuthenticationEntryPoint authenticationEntryPoint;
    private final JwtAccessDeniedHandler accessDeniedHandler;
    private final InternalTokenVerifier internalTokenVerifier;
    private final InternalTokenProperties internalTokenProperties;

    /**
     * 공통 Gateway 신뢰 보안 정책을 {@code http} 에 적용한다. 호출자가 {@code http.build()} 로 단일 체인을 완성한다.
     *
     * @param http       서비스의 SecurityFilterChain 빌더
     * @param publicUrls 해당 서비스의 인증 면제 URL
     */
    public void apply(HttpSecurity http, String[] publicUrls) throws Exception {
        InternalTokenAuthenticationFilter headerFilter =
                new InternalTokenAuthenticationFilter(internalTokenVerifier, internalTokenProperties, authenticationEntryPoint);

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
                .addFilterBefore(headerFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new MdcFilter(), InternalTokenAuthenticationFilter.class);
    }
}
