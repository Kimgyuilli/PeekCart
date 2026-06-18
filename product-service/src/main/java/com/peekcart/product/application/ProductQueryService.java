package com.peekcart.product.application;

import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.application.dto.ProductInfoDto;
import com.peekcart.product.application.dto.ProductListDto;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 조회를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductQueryService {

    private final ProductCacheService productCacheService;
    private final InventoryRepository inventoryRepository;

    /**
     * 판매 중인 상품 목록을 페이징으로 조회한다.
     *
     * @param categoryId 카테고리 필터 (null이면 전체)
     * @param pageable   페이징 정보
     * @return 상품 페이지
     */
    public Page<ProductListDto> getProducts(Long categoryId, Pageable pageable) {
        return productCacheService.getProductList(categoryId, pageable).toPage();
    }

    /**
     * 상품 상세 정보를 조회한다.
     *
     * @param productId 조회할 상품 PK
     * @return 상품 상세 DTO (상품 + 재고)
     */
    public ProductDetailDto getProduct(Long productId) {
        ProductInfoDto info = productCacheService.getProductInfo(productId);

        int stock = inventoryRepository.findByProductId(productId)
                .map(Inventory::getStock)
                .orElse(0);

        return new ProductDetailDto(
                info.id(), info.categoryId(), info.categoryName(),
                info.name(), info.description(), info.price(),
                info.imageUrl(), info.status(), stock);
    }
}
