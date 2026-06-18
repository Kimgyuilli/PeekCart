package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.ProductFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ServiceTest
@DisplayName("InventoryService 단위 테스트")
class InventoryServiceTest {

    @InjectMocks InventoryService inventoryService;

    @Mock InventoryRepository inventoryRepository;

    private final Category category = ProductFixture.categoryWithId();

    // ── decreaseStock ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("decreaseStock: 재고가 정상 차감된다")
    void decreaseStock_success_reducesInventory() {
        Product product = ProductFixture.productWithId(category);
        Inventory inventory = Inventory.create(product, 50);
        given(inventoryRepository.findByProductId(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(Optional.of(inventory));

        inventoryService.decreaseStock(ProductFixture.DEFAULT_PRODUCT_ID, 10);

        assertThat(inventory.getStock()).isEqualTo(40);
    }

    @Test
    @DisplayName("decreaseStock: 재고가 없으면 PRD-001 예외가 발생한다")
    void decreaseStock_inventoryNotFound_throwsPRD001() {
        given(inventoryRepository.findByProductId(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.decreaseStock(99L, 1))
                .isInstanceOf(ProductException.class)
                .extracting(e -> ((ProductException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRD_001);
    }

    // ── restoreStock ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("restoreStock: 재고가 정상 복구된다")
    void restoreStock_success_increasesInventory() {
        Product product = ProductFixture.productWithId(category);
        Inventory inventory = Inventory.create(product, 10);
        given(inventoryRepository.findByProductId(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(Optional.of(inventory));

        inventoryService.restoreStock(ProductFixture.DEFAULT_PRODUCT_ID, 20);

        assertThat(inventory.getStock()).isEqualTo(30);
    }

    @Test
    @DisplayName("restoreStock: 재고가 없으면 PRD-001 예외가 발생한다")
    void restoreStock_inventoryNotFound_throwsPRD001() {
        given(inventoryRepository.findByProductId(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.restoreStock(99L, 5))
                .isInstanceOf(ProductException.class)
                .extracting(e -> ((ProductException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRD_001);
    }
}
