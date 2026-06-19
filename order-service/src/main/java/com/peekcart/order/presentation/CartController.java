package com.peekcart.order.presentation;

import com.peekcart.global.auth.CurrentUser;
import com.peekcart.global.auth.LoginUser;
import com.peekcart.global.response.ApiResponse;
import com.peekcart.order.application.CartCommandService;
import com.peekcart.order.application.CartQueryService;
import com.peekcart.order.application.dto.AddCartItemCommand;
import com.peekcart.order.application.dto.UpdateCartItemCommand;
import com.peekcart.order.presentation.dto.request.AddCartItemRequest;
import com.peekcart.order.presentation.dto.request.UpdateCartItemRequest;
import com.peekcart.order.presentation.dto.response.CartResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 장바구니 API 엔드포인트. 인증 필수.
 */
@Tag(name = "장바구니", description = "장바구니 조회 / 상품 추가 / 수량 변경 / 삭제")
@RestController
@RequestMapping("/api/v1/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartCommandService cartCommandService;
    private final CartQueryService cartQueryService;

    @Operation(summary = "장바구니 조회")
    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCart(@CurrentUser LoginUser loginUser) {
        return ResponseEntity.ok(ApiResponse.of(CartResponse.from(cartQueryService.getCart(loginUser.userId()))));
    }

    @Operation(summary = "장바구니 상품 추가", description = "동일 상품이 이미 있으면 수량을 병합한다.")
    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(
            @CurrentUser LoginUser loginUser,
            @Valid @RequestBody AddCartItemRequest request
    ) {
        AddCartItemCommand command = new AddCartItemCommand(request.productId(), request.quantity());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.of(CartResponse.from(cartCommandService.addItem(loginUser.userId(), command))));
    }

    @Operation(summary = "장바구니 수량 변경")
    @PutMapping("/items/{itemId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateItem(
            @CurrentUser LoginUser loginUser,
            @PathVariable Long itemId,
            @Valid @RequestBody UpdateCartItemRequest request
    ) {
        UpdateCartItemCommand command = new UpdateCartItemCommand(request.quantity());
        return ResponseEntity.ok(ApiResponse.of(
                CartResponse.from(cartCommandService.updateItem(loginUser.userId(), itemId, command))));
    }

    @Operation(summary = "장바구니 상품 삭제")
    @DeleteMapping("/items/{itemId}")
    public ResponseEntity<Void> removeItem(
            @CurrentUser LoginUser loginUser,
            @PathVariable Long itemId
    ) {
        cartCommandService.removeItem(loginUser.userId(), itemId);
        return ResponseEntity.noContent().build();
    }
}
