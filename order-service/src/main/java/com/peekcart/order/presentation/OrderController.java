package com.peekcart.order.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.application.OrderQueryService;
import com.peekcart.order.application.dto.CreateOrderCommand;
import com.peekcart.order.presentation.dto.request.CreateOrderRequest;
import com.peekcart.order.presentation.dto.response.OrderDetailResponse;
import com.peekcart.order.presentation.dto.response.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 주문 API 엔드포인트. 인증 필수.
 */
@Tag(name = "주문", description = "주문 생성 / 조회 / 취소")
@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderCommandService orderCommandService;
    private final OrderQueryService orderQueryService;

    @Operation(summary = "주문 생성", description = "장바구니 상품으로 주문을 생성한다. 재고가 즉시 차감된다.")
    @PostMapping
    public ResponseEntity<ApiResponse<OrderDetailResponse>> createOrder(
            @CurrentUser LoginUser loginUser,
            @Valid @RequestBody CreateOrderRequest request
    ) {
        CreateOrderCommand command = new CreateOrderCommand(
                request.receiverName(), request.phone(), request.zipcode(), request.address());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(OrderDetailResponse.from(orderCommandService.createOrder(loginUser.userId(), command))));
    }

    @Operation(summary = "주문 목록 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<OrderResponse>>> getOrders(
            @CurrentUser LoginUser loginUser,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<OrderResponse> page = orderQueryService.getOrders(loginUser.userId(), pageable)
                .map(OrderResponse::from);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    @Operation(summary = "주문 상세 조회")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<OrderDetailResponse>> getOrder(
            @CurrentUser LoginUser loginUser,
            @PathVariable Long id
    ) {
        return ResponseEntity.ok(ApiResponse.of(
                OrderDetailResponse.from(orderQueryService.getOrder(loginUser.userId(), id))));
    }

    @Operation(summary = "주문 취소", description = "주문을 취소하고 차감된 재고를 복구한다.")
    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelOrder(
            @CurrentUser LoginUser loginUser,
            @PathVariable Long id
    ) {
        orderCommandService.cancelOrder(loginUser.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
