package com.peekcart.global.jwt;

import com.peekcart.global.auth.TokenBlacklistLookupPort;
import com.peekcart.global.auth.TokenClaims;
import com.peekcart.global.auth.TokenParseException;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * JwtFilter 의 인증 설정/거부 경로 단위 회귀 (ADR-0014 D1).
 * 검증 성공 + blacklist miss 일 때만 SecurityContext 에 인증을 설정한다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("JwtFilter 단위 테스트")
class JwtFilterTest {

    @Mock JwtTokenVerifier jwtTokenVerifier;
    @Mock TokenBlacklistLookupPort tokenBlacklistLookupPort;

    private static final String TOKEN = "valid.jwt.token";

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private MockHttpServletRequest bearerRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private TokenClaims claims() {
        return new TokenClaims(7L, "USER", Instant.now().plusSeconds(3600));
    }

    @Test
    @DisplayName("검증 성공 + blacklist miss: SecurityContext 에 인증을 설정한다")
    void validToken_notBlacklisted_setsAuthentication() throws Exception {
        JwtFilter filter = new JwtFilter(jwtTokenVerifier, tokenBlacklistLookupPort);
        given(jwtTokenVerifier.parseToken(TOKEN)).willReturn(claims());
        given(tokenBlacklistLookupPort.isBlacklisted(TOKEN)).willReturn(false);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(bearerRequest(), response, chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(7L);
        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("blacklist hit(또는 fail-closed true): 인증을 설정하지 않는다")
    void blacklistedToken_doesNotAuthenticate() throws Exception {
        JwtFilter filter = new JwtFilter(jwtTokenVerifier, tokenBlacklistLookupPort);
        given(jwtTokenVerifier.parseToken(TOKEN)).willReturn(claims());
        given(tokenBlacklistLookupPort.isBlacklisted(TOKEN)).willReturn(true);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("검증 실패(파싱 예외): 인증을 설정하지 않고 통과한다")
    void invalidToken_doesNotAuthenticate() throws Exception {
        JwtFilter filter = new JwtFilter(jwtTokenVerifier, tokenBlacklistLookupPort);
        given(jwtTokenVerifier.parseToken(TOKEN)).willThrow(new TokenParseException(new RuntimeException()));
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(bearerRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("토큰 없음: 인증을 설정하지 않고 통과한다")
    void noToken_doesNotAuthenticate() throws Exception {
        JwtFilter filter = new JwtFilter(jwtTokenVerifier, tokenBlacklistLookupPort);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(chain, times(1)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
