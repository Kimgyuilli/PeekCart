package com.peekcart.user.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.user.infrastructure.security.UserSecurityConfig;
import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.support.WithMockLoginUser;
import com.peekcart.user.application.AuthService;
import com.peekcart.user.application.dto.TokenResult;
import com.peekcart.user.presentation.dto.request.LoginRequest;
import com.peekcart.user.presentation.dto.request.RefreshRequest;
import com.peekcart.user.presentation.dto.request.SignupRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willDoNothing;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserSecurityConfig.class))
@Import(TestSecurityConfig.class)
@DisplayName("AuthController 슬라이스 테스트")
class AuthControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean AuthService authService;

    private static final TokenResult TOKEN_RESULT = new TokenResult("access-token", "refresh-token");

    // ── signup ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /signup: 유효한 요청이면 201과 토큰을 반환한다")
    void signup_success_returns201() throws Exception {
        given(authService.signup(any())).willReturn(TOKEN_RESULT);

        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("user@example.com", "password123", "홍길동"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh-token"));
    }

    @Test
    @DisplayName("POST /signup: 이메일 형식이 잘못되면 400을 반환한다")
    void signup_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("not-an-email", "password123", "홍길동"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /signup: 비밀번호가 8자 미만이면 400을 반환한다")
    void signup_shortPassword_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SignupRequest("user@example.com", "short", "홍길동"))))
                .andExpect(status().isBadRequest());
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /login: 유효한 요청이면 200과 토큰을 반환한다")
    void login_success_returns200() throws Exception {
        given(authService.login(any())).willReturn(TOKEN_RESULT);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest("user@example.com", "password123"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"));
    }

    @Test
    @DisplayName("POST /login: 필수 필드가 비어있으면 400을 반환한다")
    void login_blankFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest("", ""))))
                .andExpect(status().isBadRequest());
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /refresh: 유효한 요청이면 200과 새 토큰을 반환한다")
    void refresh_success_returns200() throws Exception {
        given(authService.refresh(anyString())).willReturn(TOKEN_RESULT);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest("old-refresh-token"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access-token"));
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    @WithMockLoginUser(userId = 1L, accessToken = "valid-access-token")
    @DisplayName("POST /logout: 인증된 사용자면 204를 반환한다")
    void logout_authenticated_returns204() throws Exception {
        willDoNothing().given(authService).logout("valid-access-token");

        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isNoContent());
    }
}
