package com.peekcart.notification.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.notification.application.NotificationQueryService;
import com.peekcart.notification.presentation.dto.response.NotificationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림 API 컨트롤러.
 */
@Tag(name = "알림", description = "알림 내역 조회")
@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService notificationQueryService;

    @Operation(summary = "알림 목록 조회", description = "내 알림 내역을 페이징 조회한다.")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<NotificationResponse>>> getNotifications(
            @CurrentUser LoginUser loginUser,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<NotificationResponse> page = notificationQueryService.getNotifications(loginUser.userId(), pageable)
                .map(NotificationResponse::from);
        return ResponseEntity.ok(ApiResponse.of(page));
    }
}
