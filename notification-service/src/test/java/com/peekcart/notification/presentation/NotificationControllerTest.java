package com.peekcart.notification.presentation;

import com.peekcart.global.config.TestSecurityConfig;
import com.peekcart.notification.infrastructure.security.NotificationSecurityConfig;
import com.peekcart.notification.application.NotificationQueryService;
import com.peekcart.notification.application.dto.NotificationDetailDto;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.support.WithMockLoginUser;
import com.peekcart.support.fixture.NotificationFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = NotificationController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = NotificationSecurityConfig.class))
@Import(TestSecurityConfig.class)
@DisplayName("NotificationController 슬라이스 테스트")
class NotificationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockitoBean NotificationQueryService notificationQueryService;

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/notifications: 알림 목록 조회 성공 시 200을 반환한다")
    void getNotifications_success_returns200() throws Exception {
        NotificationDetailDto dto1 = NotificationFixture.notificationDetailDto();
        NotificationDetailDto dto2 = NotificationFixture.notificationDetailDto(
                2L, NotificationType.PAYMENT_COMPLETED, "결제가 완료되었습니다.");
        PageImpl<NotificationDetailDto> page = new PageImpl<>(List.of(dto1, dto2), PageRequest.of(0, 10), 2);

        given(notificationQueryService.getNotifications(eq(1L), any())).willReturn(page);

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].type").value("ORDER_CREATED"))
                .andExpect(jsonPath("$.data.content[1].type").value("PAYMENT_COMPLETED"));
    }

    @Test
    @WithMockLoginUser
    @DisplayName("GET /api/v1/notifications: 알림이 없으면 빈 목록을 반환한다")
    void getNotifications_empty_returnsEmptyList() throws Exception {
        PageImpl<NotificationDetailDto> emptyPage = new PageImpl<>(List.of(), PageRequest.of(0, 10), 0);

        given(notificationQueryService.getNotifications(eq(1L), any())).willReturn(emptyPage);

        mockMvc.perform(get("/api/v1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").isArray())
                .andExpect(jsonPath("$.data.content.length()").value(0));
    }
}
