package com.peekcart.product.domain.model;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.support.fixture.ProductFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Inventory 도메인 단위 테스트")
class InventoryTest {

    private Product product() {
        return ProductFixture.product(ProductFixture.categoryWithId());
    }

    @Test
    @DisplayName("decrease: 재고가 정상 차감된다")
    void decrease_reducesStock() {
        Inventory inventory = Inventory.create(product(), 100);

        inventory.decrease(30);

        assertThat(inventory.getStock()).isEqualTo(70);
    }

    @Test
    @DisplayName("decrease: 재고가 부족하면 PRD-002 예외가 발생한다")
    void decrease_insufficientStock_throwsPRD002() {
        Inventory inventory = Inventory.create(product(), 10);

        assertThatThrownBy(() -> inventory.decrease(11))
                .isInstanceOf(ProductException.class)
                .extracting(e -> ((ProductException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRD_002);
    }

    @Test
    @DisplayName("decrease: 재고와 동일한 수량을 차감하면 0이 된다")
    void decrease_exactStock_becomesZero() {
        Inventory inventory = Inventory.create(product(), 50);

        inventory.decrease(50);

        assertThat(inventory.getStock()).isZero();
    }

    @Test
    @DisplayName("restore: 재고가 정상 복구된다")
    void restore_increasesStock() {
        Inventory inventory = Inventory.create(product(), 10);

        inventory.restore(20);

        assertThat(inventory.getStock()).isEqualTo(30);
    }

    @Test
    @DisplayName("restore: 복구 수량이 0이면 IllegalArgumentException이 발생한다")
    void restore_zeroQuantity_throwsIAE() {
        Inventory inventory = Inventory.create(product(), 10);

        assertThatThrownBy(() -> inventory.restore(0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("restore: 복구 수량이 음수이면 IllegalArgumentException이 발생한다")
    void restore_negativeQuantity_throwsIAE() {
        Inventory inventory = Inventory.create(product(), 10);

        assertThatThrownBy(() -> inventory.restore(-1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
