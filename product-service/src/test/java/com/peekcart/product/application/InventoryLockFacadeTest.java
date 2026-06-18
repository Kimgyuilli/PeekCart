package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.lock.DistributedLockManager;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;

@ServiceTest
@DisplayName("InventoryLockFacade 단위 테스트")
class InventoryLockFacadeTest {

    @InjectMocks InventoryLockFacade inventoryLockFacade;

    @Mock InventoryService inventoryService;
    @Mock DistributedLockManager lockManager;

    @Test
    @DisplayName("락 획득 성공 시 InventoryService에 위임한다")
    void decreaseStock_lockAcquired_delegatesToService() {
        given(lockManager.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(true);

        inventoryLockFacade.decreaseStock(1L, 5);

        then(inventoryService).should().decreaseStock(1L, 5);
        then(lockManager).should().unlock("inventory-lock:1");
    }

    @Test
    @DisplayName("락 획득 실패 시 PRD-004 예외가 발생한다")
    void decreaseStock_lockFailed_throwsPRD004() {
        given(lockManager.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(false);

        assertThatThrownBy(() -> inventoryLockFacade.decreaseStock(1L, 1))
                .isInstanceOf(ProductException.class)
                .extracting(e -> ((ProductException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRD_004);

        then(inventoryService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("InventoryService 예외 발생 시에도 락이 해제된다")
    void decreaseStock_serviceThrows_lockStillReleased() {
        given(lockManager.tryLock(anyString(), anyLong(), anyLong(), any(TimeUnit.class)))
                .willReturn(true);
        willThrow(new ProductException(ErrorCode.PRD_001))
                .given(inventoryService).decreaseStock(99L, 1);

        assertThatThrownBy(() -> inventoryLockFacade.decreaseStock(99L, 1))
                .isInstanceOf(ProductException.class);

        then(lockManager).should().unlock("inventory-lock:99");
    }
}
