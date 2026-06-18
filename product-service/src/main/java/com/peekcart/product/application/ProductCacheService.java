package com.peekcart.product.application;

import com.peekcart.global.cache.CachedPage;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.dto.ProductInfoDto;
import com.peekcart.product.application.dto.ProductListDto;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import com.peekcart.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 조회 캐싱을 담당하는 서비스.
 * <p>Spring AOP 프록시가 {@code @Cacheable}을 정상 인터셉트하도록
 * {@link ProductQueryService}와 별도 빈으로 분리하였다.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductCacheService {

    private final ProductRepository productRepository;

    /**
     * 상품 상세 정보를 캐시에서 조회하거나, 캐시 미스 시 DB에서 조회 후 캐싱한다.
     * <p>재고(stock)는 포함하지 않는다 — 재고는 변경 빈도가 높아 별도 실시간 조회한다.
     *
     * @param productId 조회할 상품 PK
     * @return 재고를 제외한 상품 정보 DTO
     * @throws ProductException 상품이 없으면 {@code PRD-001}
     */
    @Cacheable(cacheNames = "product", key = "#productId")
    public ProductInfoDto getProductInfo(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        return ProductInfoDto.of(product);
    }

    /**
     * 판매 중인 상품 목록을 캐시에서 조회하거나, 캐시 미스 시 DB에서 조회 후 캐싱한다.
     *
     * @param categoryId 카테고리 필터 (null이면 전체)
     * @param pageable   페이징 정보
     * @return 캐시 직렬화 가능한 페이지 래퍼
     */
    // Sort 미지원 — Controller에서 고정 정렬만 사용 (@PageableDefault)
    @Cacheable(cacheNames = "products",
            key = "'list:' + #categoryId + ':' + #pageable.pageNumber + ':' + #pageable.pageSize")
    public CachedPage<ProductListDto> getProductList(Long categoryId, Pageable pageable) {
        Page<Product> page;
        if (categoryId != null) {
            page = productRepository.findByCategoryIdAndStatus(categoryId, ProductStatus.ON_SALE, pageable);
        } else {
            page = productRepository.findByStatus(ProductStatus.ON_SALE, pageable);
        }
        return CachedPage.of(page.map(ProductListDto::of));
    }
}
