package com.peekcart.global.security;

import com.peekcart.internaltoken.InternalTokenContract;
import com.peekcart.internaltoken.InternalTokenFixtures;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 내부 토큰 인증 필터 3-state 계약 (ADR-0017 D3). 기존
 * {@code HeaderAuthenticationFilterTest}(평문 3-state)를 서명 기준으로 재작성한 것이다.
 *
 * <p>핵심 회귀: <b>SIGNED_ONLY 에서 평문 {@code X-User-*} 를 직접 주입해도 신원이 서지 않는다</b> —
 * NetworkPolicy 를 뚫고 들어온 직접 요청이 헤더만으로 인증되면 서명 도입이 무의미해진다.
 */
@DisplayName("InternalTokenAuthenticationFilter — 3-state · 평문 무시 · 모드 전환")
class InternalTokenAuthenticationFilterTest {

    private AuthenticationEntryPoint entryPoint;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        entryPoint = mock(AuthenticationEntryPoint.class);
        chain = mock(FilterChain.class);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private InternalTokenAuthenticationFilter filter(InternalTokenProperties.Mode mode) {
        InternalTokenProperties properties = new InternalTokenProperties(
                mode,
                List.of(new InternalTokenProperties.PublicKeyEntry(InternalTokenFixtures.KID,
                        pem(InternalTokenFixtures.gatewayPublicKeyPem()))),
                5, 120);
        InternalGatewayPublicKeyRegistry registry = new InternalGatewayPublicKeyRegistry(properties);
        registry.load();
        InternalTokenVerifier verifier = new InternalTokenVerifier(registry, properties,
                Clock.fixed(InternalTokenFixtures.ISSUED_AT.plusSeconds(5), ZoneOffset.UTC));
        return new InternalTokenAuthenticationFilter(verifier, properties, entryPoint);
    }

    private static Resource pem(String content) {
        return new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8));
    }

    private static String forgedToken() {
        try {
            byte[] der = Base64.getDecoder().decode(InternalTokenFixtures.foreignPrivateKeyPem()
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", ""));
            RSAPrivateKey key = (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(der));
            return Jwts.builder()
                    .header().keyId(InternalTokenFixtures.KID).and()
                    .issuer(InternalTokenContract.ISSUER)
                    .subject("999")
                    .claim(InternalTokenContract.CLAIM_ROLE, "ADMIN")
                    .claim(InternalTokenContract.CLAIM_FAMILY_ID, "evil")
                    .issuedAt(Date.from(InternalTokenFixtures.ISSUED_AT))
                    .expiration(Date.from(InternalTokenFixtures.EXPIRES_AT))
                    .signWith(key, Jwts.SIG.RS256)
                    .compact();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Authentication authentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    @Nested
    @DisplayName("SIGNED_ONLY (기본)")
    class SignedOnly {

        @Test
        @DisplayName("토큰 부재 → anonymous 로 체인 계속 (공개 경로 보존)")
        void noToken_passesAnonymously() throws Exception {
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter(InternalTokenProperties.Mode.SIGNED_ONLY)
                    .doFilter(new MockHttpServletRequest(), response, chain);

            verify(chain).doFilter(any(), any());
            assertThat(authentication()).isNull();
        }

        @Test
        @DisplayName("평문 X-User-* 직접 주입 → 무시되고 신원이 서지 않는다 (스푸핑 표면 0)")
        void plaintextHeaders_areIgnored() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-Id", "999");
            request.addHeader("X-User-Role", "ADMIN");
            request.addHeader("X-User-Family-Id", "evil");

            filter(InternalTokenProperties.Mode.SIGNED_ONLY)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(chain).doFilter(any(), any());
            assertThat(authentication())
                    .as("평문 헤더만으로 인증되면 직접 경로 위조가 그대로 성립한다")
                    .isNull();
        }

        @Test
        @DisplayName("유효 서명 → LoginUser 로 인증 (principal/authority/details)")
        void validToken_authenticates() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalTokenContract.HEADER, InternalTokenFixtures.conformanceToken());

            filter(InternalTokenProperties.Mode.SIGNED_ONLY)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(chain).doFilter(any(), any());
            Authentication auth = authentication();
            assertThat(auth).isNotNull();
            assertThat(auth.getPrincipal()).isEqualTo(InternalTokenFixtures.USER_ID);
            assertThat(auth.getAuthorities().stream().map(GrantedAuthority::getAuthority))
                    .containsExactly("ROLE_" + InternalTokenFixtures.ROLE);
            assertThat(auth.getDetails()).isEqualTo(InternalTokenFixtures.FAMILY_ID);
        }

        @Test
        @DisplayName("위조 서명 → 401 (체인 진입 없음)")
        void forgedSignature_rejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalTokenContract.HEADER, forgedToken());

            filter(InternalTokenProperties.Mode.SIGNED_ONLY)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(entryPoint).commence(any(), any(), any());
            verify(chain, never()).doFilter(any(), any());
            assertThat(authentication()).isNull();
        }

        @Test
        @DisplayName("blank 토큰 헤더 → 401 (부재와 구분한다)")
        void blankToken_rejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalTokenContract.HEADER, "   ");

            filter(InternalTokenProperties.Mode.SIGNED_ONLY)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(entryPoint).commence(any(), any(), any());
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("중복 토큰 헤더 → 401 (어느 값을 믿을지 정의되지 않음)")
        void duplicateTokenHeaders_rejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalTokenContract.HEADER, InternalTokenFixtures.conformanceToken());
            request.addHeader(InternalTokenContract.HEADER, forgedToken());

            filter(InternalTokenProperties.Mode.SIGNED_ONLY)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(entryPoint).commence(any(), any(), any());
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("평문 헤더 + 위조 토큰 조합에서도 평문으로 강등되지 않는다 → 401")
        void forgedTokenWithPlaintextFallback_stillRejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalTokenContract.HEADER, forgedToken());
            request.addHeader("X-User-Id", "999");
            request.addHeader("X-User-Role", "ADMIN");

            filter(InternalTokenProperties.Mode.SIGNED_ONLY)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(entryPoint).commence(any(), any(), any());
            assertThat(authentication()).isNull();
        }
    }

    @Nested
    @DisplayName("DUAL_ACCEPT (롤아웃 전환기)")
    class DualAccept {

        @Test
        @DisplayName("토큰 부재 + 정상 평문 헤더 → 인증 (구 Gateway 이미지 병행)")
        void plaintextHeaders_authenticate() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-Id", "7");
            request.addHeader("X-User-Role", "USER");
            request.addHeader("X-User-Family-Id", "fam-7");

            filter(InternalTokenProperties.Mode.DUAL_ACCEPT)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(chain).doFilter(any(), any());
            assertThat(authentication()).isNotNull();
            assertThat(authentication().getPrincipal()).isEqualTo(7L);
            assertThat(authentication().getDetails()).isEqualTo("fam-7");
        }

        @Test
        @DisplayName("토큰 부재 + 형식 오류 평문 헤더 → 401 (PR3c 3-state 승계)")
        void malformedPlaintextHeaders_rejected() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-User-Id", "not-a-number");
            request.addHeader("X-User-Role", "USER");

            filter(InternalTokenProperties.Mode.DUAL_ACCEPT)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            verify(entryPoint).commence(any(), any(), any());
            verify(chain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("서명 토큰이 있으면 평문보다 우선한다 (평문 권한 상승 무력화)")
        void signedTokenTakesPrecedence() throws Exception {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(InternalTokenContract.HEADER, InternalTokenFixtures.conformanceToken());
            request.addHeader("X-User-Id", "999");
            request.addHeader("X-User-Role", "ADMIN");

            filter(InternalTokenProperties.Mode.DUAL_ACCEPT)
                    .doFilter(request, new MockHttpServletResponse(), chain);

            assertThat(authentication().getPrincipal()).isEqualTo(InternalTokenFixtures.USER_ID);
            assertThat(authentication().getAuthorities().stream().map(GrantedAuthority::getAuthority))
                    .containsExactly("ROLE_USER");
        }

        @Test
        @DisplayName("헤더 전부 부재 → anonymous 통과")
        void noHeaders_passesAnonymously() throws Exception {
            filter(InternalTokenProperties.Mode.DUAL_ACCEPT)
                    .doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), chain);

            verify(chain).doFilter(any(), any());
            assertThat(authentication()).isNull();
        }
    }
}
