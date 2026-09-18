package com.peekcart.order.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.application.OrderQueryService;
import com.peekcart.order.application.dto.CreateOrderCommand;
import com.peekcart.order.application.dto.CursorSlice;
import com.peekcart.order.application.dto.OrderSummaryDto;
import com.peekcart.order.presentation.dto.request.CreateOrderRequest;
import com.peekcart.order.presentation.dto.request.OrderPageQuery;
import com.peekcart.order.presentation.dto.response.CursorPageResponse;
import com.peekcart.order.presentation.dto.response.OrderDetailResponse;
import com.peekcart.order.presentation.dto.response.OrderResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

    @Operation(summary = "주문 생성", description = "장바구니 상품으로 주문을 생성한다. 재고 차감은 동기로 일어나지 않고 order.created 이벤트를 받은 Product 예약 Saga 가 처리한다.")
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

    /**
     * 파라미터를 {@link HttpServletRequest} 에서 읽으므로 springdoc 이 자동 생성할 대상이 없다.
     * 이름과 위치를 직접 선언해야 문서가 만들어진다.
     */
    @Operation(summary = "주문 목록 조회",
            description = "최신순(ordered_at, id) 커서 페이지네이션. 총 건수와 페이지 번호는 제공하지 않는다. "
                    + "nextCursor 는 불투명 문자열이며 형식에 의존하지 말 것.",
            parameters = {
                    @Parameter(name = "cursor", in = ParameterIn.QUERY, required = false,
                            description = "이전 응답의 nextCursor. 생략하면 첫 페이지",
                            schema = @Schema(type = "string")),
                    @Parameter(name = "size", in = ParameterIn.QUERY, required = false,
                            schema = @Schema(type = "integer", format = "int32",
                                    defaultValue = "20", minimum = "1", maximum = "100"))
            })
    @GetMapping
    public ResponseEntity<ApiResponse<CursorPageResponse<OrderResponse>>> getOrders(
            @CurrentUser LoginUser loginUser,
            HttpServletRequest request
    ) {
        OrderPageQuery query = OrderPageQuery.of(request);
        CursorSlice<OrderSummaryDto> slice =
                orderQueryService.getOrders(loginUser.userId(), query.cursor(), query.size());
        return ResponseEntity.ok(ApiResponse.of(new CursorPageResponse<>(
                slice.content().stream().map(OrderResponse::from).toList(),
                slice.nextCursor(),
                slice.hasNext())));
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
