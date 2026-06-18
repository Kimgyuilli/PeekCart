package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.lock.DistributedLockManager;
import com.peekcart.product.domain.exception.ProductException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 분산 락과 트랜잭션 순서를 보장하는 파사드.
 * 락 획득 → {@link InventoryService}(트랜잭션) → 커밋 → 락 해제 순서를 강제한다.
 */
@Component
@RequiredArgsConstructor
public class InventoryLockFacade {

    private static final String LOCK_KEY_PREFIX = "inventory-lock:";
    private static final long LOCK_WAIT_TIME = 3;
    private static final long LOCK_LEASE_TIME = 5;

    private final InventoryService inventoryService;
    private final DistributedLockManager lockManager;

    /**
     * 분산 락을 획득한 뒤 재고를 차감한다.
     * 락 획득 → 트랜잭션(커밋) → 락 해제 순서로 실행되어 동시성을 보장한다.
     *
     * @param productId 상품 PK
     * @param quantity  차감 수량
     * @throws ProductException 락 획득 실패 시 {@code PRD-004}
     */
    public void decreaseStock(Long productId, int quantity) {
        String lockKey = LOCK_KEY_PREFIX + productId;
        boolean locked = lockManager.tryLock(lockKey, LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.SECONDS);
        if (!locked) {
            throw new ProductException(ErrorCode.PRD_004);
        }
        try {
            inventoryService.decreaseStock(productId, quantity);
        } finally {
            lockManager.unlock(lockKey);
        }
    }
}
