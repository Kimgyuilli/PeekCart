package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.repository.InventoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 재고 차감/복구를 담당하는 애플리케이션 서비스.
 * 트랜잭션 내부에서 DB 연산만 수행하며, 분산 락은 {@link InventoryLockFacade}가 담당한다.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    /**
     * 재고를 차감한다.
     * 반드시 {@link InventoryLockFacade}를 통해 호출하여 분산 락 범위 안에서 실행되어야 한다.
     *
     * @param productId 상품 PK
     * @param quantity  차감 수량
     * @throws ProductException 상품 재고가 없으면 {@code PRD-001}, 재고 부족이면 {@code PRD-002}
     */
    public void decreaseStock(Long productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        inventory.decrease(quantity);
    }

    /**
     * 재고를 복구한다.
     *
     * @param productId 상품 PK
     * @param quantity  복구 수량
     * @throws ProductException 상품 재고가 없으면 {@code PRD-001}
     */
    public void restoreStock(Long productId, int quantity) {
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        inventory.restore(quantity);
    }

    /**
     * 재고가 요청 수량 이상인지 확인한다 (예약 all-or-nothing 선검사용, read-only).
     *
     * @return 재고가 충분하면 true. 상품 재고가 없거나 부족하면 false
     */
    @Transactional(readOnly = true)
    public boolean hasSufficientStock(Long productId, int quantity) {
        return inventoryRepository.findByProductId(productId)
                .map(inventory -> inventory.getStock() >= quantity)
                .orElse(false);
    }
}
