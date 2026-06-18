package com.peekcart.product.presentation;

import com.peekcart.global.response.ApiResponse;
import com.peekcart.product.application.ProductCommandService;
import com.peekcart.product.application.dto.CreateProductCommand;
import com.peekcart.product.application.dto.UpdateProductCommand;
import com.peekcart.product.presentation.dto.request.CreateProductRequest;
import com.peekcart.product.presentation.dto.request.UpdateProductRequest;
import com.peekcart.product.presentation.dto.response.ProductDetailResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * 상품 관리 API 엔드포인트.
 * 관리자(ADMIN) 권한이 필요하다.
 */
@Tag(name = "상품 관리", description = "상품 CRUD (ADMIN 전용)")
@RestController
@RequestMapping("/api/v1/admin/products")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminProductController {

    private final ProductCommandService productCommandService;

    @Operation(summary = "상품 등록", description = "상품과 초기 재고를 함께 생성한다.")
    @PostMapping
    public ResponseEntity<ApiResponse<ProductDetailResponse>> createProduct(
            @Valid @RequestBody CreateProductRequest request
    ) {
        CreateProductCommand command = new CreateProductCommand(
                request.categoryId(), request.name(), request.description(),
                request.price(), request.imageUrl(), request.stock());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.of(
                ProductDetailResponse.from(productCommandService.create(command))));
    }

    @Operation(summary = "상품 수정")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> updateProduct(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequest request
    ) {
        UpdateProductCommand command = new UpdateProductCommand(
                request.categoryId(), request.name(), request.description(),
                request.price(), request.imageUrl());
        return ResponseEntity.ok(ApiResponse.of(
                ProductDetailResponse.from(productCommandService.update(id, command))));
    }

    @Operation(summary = "상품 삭제", description = "상품 상태를 DISCONTINUED로 변경한다 (soft delete).")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        productCommandService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
