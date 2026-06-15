package com.peekcart.user.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.user.application.UserCommandService;
import com.peekcart.user.application.UserQueryService;
import com.peekcart.user.presentation.dto.request.UpdateProfileRequest;
import com.peekcart.user.presentation.dto.response.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 회원 정보 API 엔드포인트.
 * 내 정보 조회 및 프로필 수정을 처리한다.
 */
@Tag(name = "회원", description = "내 정보 조회 / 프로필 수정")
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserQueryService userQueryService;
    private final UserCommandService userCommandService;

    @Operation(summary = "내 정보 조회")
    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> getMe(@CurrentUser LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.of(UserResponse.from(userQueryService.getMe(loginUser.userId()))));
    }

    @Operation(summary = "프로필 수정")
    @PutMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<ApiResponse<UserResponse>> updateMe(
            @CurrentUser LoginUser loginUser,
            @Valid @RequestBody UpdateProfileRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.of(UserResponse.from(userCommandService.updateMe(loginUser.userId(), request))));
    }
}
