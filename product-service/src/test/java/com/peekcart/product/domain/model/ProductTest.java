package com.peekcart.product.domain.model;

import com.peekcart.support.fixture.ProductFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Product 도메인 단위 테스트")
class ProductTest {

    private final Category category = ProductFixture.categoryWithId();

    @Test
    @DisplayName("create: 기본 상태가 ON_SALE로 설정된다")
    void create_setsStatusToOnSale() {
        Product product = Product.create(category, "상품명", "설명", 1000L, null);

        assertThat(product.getStatus()).isEqualTo(ProductStatus.ON_SALE);
        assertThat(product.isOnSale()).isTrue();
    }

    @Test
    @DisplayName("create: 가격이 0이면 정상 생성된다")
    void create_zeroPriceIsValid() {
        Product product = Product.create(category, "무료상품", null, 0L, null);

        assertThat(product.getPrice()).isZero();
    }

    @Test
    @DisplayName("create: 가격이 음수이면 IllegalArgumentException이 발생한다")
    void create_negativePrice_throwsIAE() {
        assertThatThrownBy(() -> Product.create(category, "상품명", "설명", -1L, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update: 전달된 값으로 필드가 변경된다")
    void update_changesFields() {
        Product product = ProductFixture.product(category);
        Category newCategory = Category.create("패션", null);

        product.update(newCategory, "새상품명", "새설명", 2000L, "https://new.jpg");

        assertThat(product.getName()).isEqualTo("새상품명");
        assertThat(product.getDescription()).isEqualTo("새설명");
        assertThat(product.getPrice()).isEqualTo(2000L);
        assertThat(product.getImageUrl()).isEqualTo("https://new.jpg");
        assertThat(product.getCategory()).isSameAs(newCategory);
    }

    @Test
    @DisplayName("discontinue: 상태가 DISCONTINUED로 변경된다")
    void discontinue_setsStatusToDiscontinued() {
        Product product = ProductFixture.product(category);

        product.discontinue();

        assertThat(product.getStatus()).isEqualTo(ProductStatus.DISCONTINUED);
        assertThat(product.isOnSale()).isFalse();
    }
}
