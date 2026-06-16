package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.dto.CreateProductCommand;
import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.application.dto.UpdateProductCommand;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.repository.CategoryRepository;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.domain.repository.ProductRepository;
import com.peekcart.product.infrastructure.outbox.ProductOutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 등록/수정/삭제를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class ProductCommandService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final InventoryRepository inventoryRepository;
    private final ProductOutboxEventPublisher outboxEventPublisher;

    /**
     * 새 상품을 등록하고 초기 재고를 생성한다.
     *
     * @param command 상품 등록 커맨드
     * @return 생성된 상품 상세 정보
     * @throws ProductException 카테고리가 없으면 {@code PRD-003}
     */
    @CacheEvict(cacheNames = "products", allEntries = true)
    public ProductDetailDto create(CreateProductCommand command) {
        Category category = categoryRepository.findById(command.categoryId())
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_003));

        Product product = Product.create(
                category, command.name(), command.description(), command.price(), command.imageUrl());
        productRepository.save(product);

        Inventory inventory = Inventory.create(product, command.stock());
        inventoryRepository.save(inventory);

        // version 은 flush 후에야 채워진다 → product.updated 발행 전 saveAndFlush (strangler-2, 라운드3 #1)
        productRepository.saveAndFlush(product);
        outboxEventPublisher.publishProductUpdated(product, inventory.getStock());

        return ProductDetailDto.of(product, inventory.getStock());
    }

    /**
     * 상품 정보를 수정한다.
     *
     * @param productId 수정할 상품 PK
     * @param command   수정 커맨드
     * @return 수정된 상품 상세 정보
     * @throws ProductException 상품이 없으면 {@code PRD-001}, 카테고리가 없으면 {@code PRD-003}
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "product", key = "#productId"),
            @CacheEvict(cacheNames = "products", allEntries = true)
    })
    public ProductDetailDto update(Long productId, UpdateProductCommand command) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        Category category = categoryRepository.findById(command.categoryId())
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_003));

        product.update(category, command.name(), command.description(), command.price(), command.imageUrl());

        int stock = inventoryRepository.findByProductId(productId)
                .map(Inventory::getStock)
                .orElse(0);

        // 변경분 flush 로 version 증가 후 발행 (flush 전 읽으면 seed=0 ↔ event=0 충돌, 라운드3 #1)
        productRepository.saveAndFlush(product);
        outboxEventPublisher.publishProductUpdated(product, stock);

        return ProductDetailDto.of(product, stock);
    }

    /**
     * 상품을 판매 중단 처리한다 (soft delete).
     *
     * @param productId 삭제할 상품 PK
     * @throws ProductException 상품이 없으면 {@code PRD-001}
     */
    @Caching(evict = {
            @CacheEvict(cacheNames = "product", key = "#productId"),
            @CacheEvict(cacheNames = "products", allEntries = true)
    })
    public void delete(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        product.discontinue();

        int stock = inventoryRepository.findByProductId(productId)
                .map(Inventory::getStock)
                .orElse(0);

        // 판매중단도 status 변경이므로 product.updated 발행 (status=INACTIVE, 라운드2 #3)
        productRepository.saveAndFlush(product);
        outboxEventPublisher.publishProductUpdated(product, stock);
    }
}
