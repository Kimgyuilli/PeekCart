package com.peekcart.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * 인증 없이 보호된 리소스에 접근했을 때 프로젝트 표준 에러 포맷으로 401을 반환한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException e) throws IOException {
        ErrorCode errorCode = ErrorCode.USR_004;
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                new ErrorResponse(errorCode.getHttpStatus().value(), errorCode.getCode(),
                        errorCode.getMessage(), Instant.now()));
    }
}
