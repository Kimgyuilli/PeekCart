package com.peekcart.global.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.exception.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;

/**
 * 인증은 됐지만 권한이 없을 때 프로젝트 표준 에러 포맷으로 403을 반환한다.
 */
@Component
@RequiredArgsConstructor
public class JwtAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException e) throws IOException {
        ErrorCode errorCode = ErrorCode.SYS_004;
        response.setStatus(errorCode.getHttpStatus().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getWriter(),
                new ErrorResponse(errorCode.getHttpStatus().value(), errorCode.getCode(),
                        errorCode.getMessage(), Instant.now()));
    }
}
