package com.peekcart.user.presentation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.user.infrastructure.security.UserSecurityConfig;
import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.support.WithMockLoginUser;
import com.peekcart.support.fixture.UserFixture;
import com.peekcart.user.application.UserCommandService;
import com.peekcart.user.application.UserQueryService;
import com.peekcart.user.presentation.dto.request.UpdateProfileRequest;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = UserController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = UserSecurityConfig.class))
@Import(TestSecurityConfig.class)
@DisplayName("UserController 슬라이스 테스트")
class UserControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockitoBean UserQueryService userQueryService;
    @MockitoBean UserCommandService userCommandService;

    // ── GET /me ───────────────────────────────────────────────────────────────

    @Test
    @WithMockLoginUser(userId = 1L)
    @DisplayName("GET /me: 인증된 사용자면 회원 정보를 반환한다")
    void getMe_authenticated_returnsUserResponse() throws Exception {
        given(userQueryService.getMe(1L)).willReturn(UserFixture.userWithId());

        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(1L))
                .andExpect(jsonPath("$.data.email").value(UserFixture.DEFAULT_EMAIL))
                .andExpect(jsonPath("$.data.name").value(UserFixture.DEFAULT_NAME));
    }

    // ── PUT /me ───────────────────────────────────────────────────────────────

    @Test
    @WithMockLoginUser(userId = 1L)
    @DisplayName("PUT /me: 유효한 요청이면 수정된 회원 정보를 반환한다")
    void updateMe_success_returnsUpdatedUserResponse() throws Exception {
        given(userCommandService.updateMe(eq(1L), any()))
                .willReturn(UserFixture.userWithEmail(UserFixture.DEFAULT_EMAIL));

        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProfileRequest("새이름"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(UserFixture.DEFAULT_EMAIL));
    }

    @Test
    @WithMockLoginUser(userId = 1L)
    @DisplayName("PUT /me: 이름이 공백이면 400을 반환한다")
    void updateMe_blankName_returns400() throws Exception {
        mockMvc.perform(put("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateProfileRequest(""))))
                .andExpect(status().isBadRequest());
    }
}
