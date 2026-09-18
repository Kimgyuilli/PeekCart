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
 *
 * <p>동시성 제어 수단은 {@code Inventory.@Version}(낙관적 락) <b>하나</b>다 (ADR-0025 D1).
 * 이전에는 Redis 분산 락({@code InventoryLockFacade})이 앞단에 있었으나, 호출자(consumer)의
 * 트랜잭션에 REQUIRED 로 참여하는 구조 때문에 락 해제가 커밋보다 먼저 일어나 <b>락 구간에 쓰기가
 * 들어가지 않았다</b> — 락은 전원이 획득하면서 직렬화에는 기여하지 않았다(D-025).
 */
@Service
@Transactional
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    /**
     * 재고를 차감한다. 동시 차감의 정합성은 {@code @Version} 이 지킨다 —
     * 충돌 시 호출자(consumer) 트랜잭션이 통째로 롤백되고 재시도로 수렴한다 (ADR-0025 D1/D2).
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
