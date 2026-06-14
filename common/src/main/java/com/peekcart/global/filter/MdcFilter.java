package com.peekcart.global.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * JwtFilter 뒤에서 실행되어 MDC에 traceId와 userId를 설정하는 필터.
 * traceId는 요청마다 UUID 기반 16자리로 생성하고,
 * userId는 SecurityContext에서 인증된 사용자 ID를 추출한다.
 */
public class MdcFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        try {
            MDC.put("traceId", UUID.randomUUID().toString().replace("-", "").substring(0, 16));

            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Long userId) {
                MDC.put("userId", userId.toString());
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }
}
