package com.peekcart.global.security;

import com.peekcart.global.auth.LoginUser;
import com.peekcart.internaltoken.InternalTokenContract;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Gateway 서명 내부 토큰({@link InternalTokenContract#HEADER})으로 인증을 세우는 필터
 * (ADR-0017 D3).
 *
 * <p><b>3-state 계약</b>:
 * <ol>
 *   <li><b>토큰 부재</b> → anonymous 로 체인 계속(공개 경로 보존, 보호 경로는 authorizeHttpRequests 가 401)</li>
 *   <li><b>유효 서명</b> → {@link LoginUser} 인증 세팅(principal=userId, authority={@code ROLE_<role>}, details=familyId)</li>
 *   <li><b>무효</b>(서명오류·만료·wrong iss/kid·과수명·future-iat·claim 누락·중복 헤더) → 401 (500·anonymous fallback 아님)</li>
 * </ol>
 *
 * <p><b>평문 {@code X-User-*} 처리</b>: {@link InternalTokenProperties.Mode#SIGNED_ONLY} 에서는 평문 헤더를
 * <b>무시</b>한다(401 아님) — 위조 헤더를 401 로 만들면 공개 경로가 헤더 하나로 막히고, 무시하면 신원이
 * 서지 않아 보호 경로가 정상적으로 401 이 된다. {@link InternalTokenProperties.Mode#DUAL_ACCEPT} 는
 * 롤아웃 전환기에서만 평문을 수용하며, signed-only 전환이 끝나면 이 분기는 제거 대상이다.
 */
@RequiredArgsConstructor
public class InternalTokenAuthenticationFilter extends OncePerRequestFilter {

    /** 전환기 평문 신뢰 헤더 — DUAL_ACCEPT 에서만 읽는다. */
    static final String USER_ID_HEADER = "X-User-Id";
    static final String USER_ROLE_HEADER = "X-User-Role";
    static final String USER_FAMILY_ID_HEADER = "X-User-Family-Id";

    private static final Set<String> ALLOWED_ROLES = Set.of("USER", "ADMIN");
    private static final Logger log = LoggerFactory.getLogger(InternalTokenAuthenticationFilter.class);

    private final InternalTokenVerifier verifier;
    private final InternalTokenProperties properties;
    private final AuthenticationEntryPoint authenticationEntryPoint;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        List<String> tokens = headerValues(request, InternalTokenContract.HEADER);

        if (tokens.isEmpty()) {
            if (properties.mode() == InternalTokenProperties.Mode.DUAL_ACCEPT) {
                authenticateWithPlaintextHeaders(request, response, filterChain);
                return;
            }
            // SIGNED_ONLY: 평문 X-User-* 가 실려 있어도 신원으로 삼지 않는다(무시).
            filterChain.doFilter(request, response);
            return;
        }

        // 중복 헤더는 "어느 값을 믿을지" 가 정의되지 않는다 → 거부.
        if (tokens.size() > 1 || !StringUtils.hasText(tokens.get(0))) {
            reject(request, response, "내부 토큰 헤더 형식 오류");
            return;
        }

        LoginUser loginUser;
        try {
            loginUser = verifier.verify(tokens.get(0).trim());
        } catch (InternalTokenVerifier.InvalidInternalTokenException e) {
            log.debug("내부 토큰 거부: {}", e.getMessage());
            reject(request, response, "내부 토큰 검증 실패");
            return;
        }

        authenticate(loginUser.userId(), loginUser.role(), loginUser.familyId());
        filterChain.doFilter(request, response);
    }

    /**
     * 전환기 평문 경로 — 위 3-state 계약을 그대로 유지한다.
     * signed-only 전환이 끝나면 이 메서드와 {@link InternalTokenProperties.Mode#DUAL_ACCEPT}
     * 는 함께 제거 대상이다.
     */
    private void authenticateWithPlaintextHeaders(HttpServletRequest request, HttpServletResponse response,
                                                  FilterChain filterChain) throws ServletException, IOException {
        List<String> ids = headerValues(request, USER_ID_HEADER);
        List<String> roles = headerValues(request, USER_ROLE_HEADER);
        List<String> familyIds = headerValues(request, USER_FAMILY_ID_HEADER);

        if (ids.isEmpty() && roles.isEmpty() && familyIds.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        Long userId = parseSingleUserId(ids);
        String role = parseSingleRole(roles);
        boolean familyViolation = familyIds.size() > 1
                || (familyIds.size() == 1 && !StringUtils.hasText(familyIds.get(0)));
        if (userId == null || role == null || familyViolation) {
            reject(request, response, "신뢰 헤더 형식 오류");
            return;
        }

        authenticate(userId, role, familyIds.isEmpty() ? null : familyIds.get(0).trim());
        filterChain.doFilter(request, response);
    }

    private void authenticate(Long userId, String role, String familyId) {
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_" + role))
        );
        authentication.setDetails(familyId);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    /** 헤더의 모든 값을 원본 그대로 반환(부재=빈 리스트, 중복 헤더는 복수 값, present-blank 는 blank 값 포함). */
    private List<String> headerValues(HttpServletRequest request, String name) {
        return Collections.list(request.getHeaders(name));
    }

    /** 정확히 1개 + 양의 정수. 위반이면 {@code null}. */
    private Long parseSingleUserId(List<String> ids) {
        if (ids.size() != 1) {
            return null;
        }
        try {
            long value = Long.parseLong(ids.get(0).trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 정확히 1개 + 허용 role. 위반이면 {@code null}. */
    private String parseSingleRole(List<String> roles) {
        if (roles.size() != 1) {
            return null;
        }
        String role = roles.get(0).trim();
        return ALLOWED_ROLES.contains(role) ? role : null;
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        try {
            authenticationEntryPoint.commence(request, response, new InsufficientAuthenticationException(message));
        } catch (ServletException e) {
            throw new IOException(e);
        }
    }
}
