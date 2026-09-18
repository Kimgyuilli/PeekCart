package com.peekcart.product.application;

import com.peekcart.global.cache.CachedPage;
import com.peekcart.global.config.CacheConfig;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.dto.ProductInfoDto;
import com.peekcart.product.application.dto.ProductListDto;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import com.peekcart.product.domain.repository.InventoryRepository;
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
    private final InventoryRepository inventoryRepository;

    /**
     * 상품 상세 정보를 캐시에서 조회하거나, 캐시 미스 시 DB에서 조회 후 캐싱한다.
     * <p>재고(stock)는 포함하지 않는다 — 변경 빈도가 달라 TTL 이 다르기 때문이며,
     * 재고는 {@link #getStock(Long)} 이 별도 캐시로 제공한다 (ADR-0026 D2).
     *
     * @param productId 조회할 상품 PK
     * @return 재고를 제외한 상품 정보 DTO
     * @throws ProductException 상품이 없으면 {@code PRD-001}
     */
    @Cacheable(cacheNames = CacheConfig.PRODUCT_DETAIL_CACHE, key = "#productId")
    public ProductInfoDto getProductInfo(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        return ProductInfoDto.of(product);
    }

    /**
     * 재고를 짧은 TTL 캐시에서 조회하거나, 미스 시 DB에서 조회 후 캐싱한다 (ADR-0026 D2).
     *
     * <p><b>이 값은 예약을 보증하지 않는다</b> — 상세 조회의 재고는 <b>표시용 힌트</b>이고,
     * 주문 가능 여부의 진실은 예약 Saga({@code stock.reservation.result})와 {@code PRD-002} 가
     * 정한다 (ADR-0026 D1). 캐시 이전에도 응답 직후 값이 변할 수 있었으므로, TTL 은 새로운
     * 부정확성을 만드는 것이 아니라 이미 있던 창을 <b>유계로</b> 넓힌다.
     *
     * <p><b>쓰기 경로는 이 캐시를 무효화하지 않는다</b>(ADR-0026 D3). 예약/복구는
     * consumer 트랜잭션에 REQUIRED 로 참여하므로 {@code @CacheEvict} 가 <b>커밋보다 먼저</b>
     * 돌고, 그 창의 재조회가 미커밋 구값을 되캐싱하면 stale 이 TTL 까지 고착된다(ADR-0025 와
     * 같은 함정). 정확성의 상한은 evict 이 아니라 <b>TTL</b> 이 준다.
     *
     * <p>재고 행이 없으면 0 을 반환한다 — 상세 조회는 재고 부재로 실패하지 않는다(기존 동작 유지).
     *
     * @param productId 조회할 상품 PK
     * @return 가용 재고. 재고 행이 없으면 0
     */
    @Cacheable(cacheNames = CacheConfig.PRODUCT_STOCK_CACHE, key = "#productId")
    public int getStock(Long productId) {
        return inventoryRepository.findByProductId(productId)
                .map(Inventory::getStock)
                .orElse(0);
    }

    /**
     * 판매 중인 상품 목록을 캐시에서 조회하거나, 캐시 미스 시 DB에서 조회 후 캐싱한다.
     *
     * @param categoryId 카테고리 필터 (null이면 전체)
     * @param pageable   페이징 정보
     * @return 캐시 직렬화 가능한 페이지 래퍼
     */
    // Sort 미지원 — Controller에서 고정 정렬만 사용 (@PageableDefault)
    @Cacheable(cacheNames = CacheConfig.PRODUCT_LIST_CACHE,
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
