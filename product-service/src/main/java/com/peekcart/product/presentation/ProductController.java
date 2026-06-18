package com.peekcart.product.presentation;

import com.peekcart.global.response.ApiResponse;
import com.peekcart.product.application.ProductQueryService;
import com.peekcart.product.presentation.dto.response.ProductDetailResponse;
import com.peekcart.product.presentation.dto.response.ProductResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 상품 공개 조회 API 엔드포인트.
 * 인증 없이 접근할 수 있다.
 */
@Tag(name = "상품", description = "상품 목록 / 상세 조회 (공개)")
@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductQueryService productQueryService;

    @Operation(summary = "상품 목록 조회", description = "판매 중(ON_SALE) 상품을 페이징 조회한다. 카테고리 필터 가능.")
    @GetMapping
    public ResponseEntity<ApiResponse<Page<ProductResponse>>> getProducts(
            @RequestParam(required = false) Long categoryId,
            @ParameterObject @PageableDefault(size = 20) Pageable pageable
    ) {
        Page<ProductResponse> page = productQueryService.getProducts(categoryId, pageable)
                .map(ProductResponse::from);
        return ResponseEntity.ok(ApiResponse.of(page));
    }

    @Operation(summary = "상품 상세 조회")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ProductDetailResponse>> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.of(
                ProductDetailResponse.from(productQueryService.getProduct(id))));
    }
}
