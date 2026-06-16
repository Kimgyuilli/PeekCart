package com.peekcart.product.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.product.application.dto.CreateProductCommand;
import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.application.dto.UpdateProductCommand;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.model.ProductStatus;
import com.peekcart.product.domain.repository.CategoryRepository;
import com.peekcart.product.domain.repository.InventoryRepository;
import com.peekcart.product.domain.repository.ProductRepository;
import com.peekcart.product.infrastructure.outbox.ProductOutboxEventPublisher;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.ProductFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ServiceTest
@DisplayName("ProductCommandService 단위 테스트")
class ProductCommandServiceTest {

    @InjectMocks ProductCommandService productCommandService;

    @Mock ProductRepository productRepository;
    @Mock CategoryRepository categoryRepository;
    @Mock InventoryRepository inventoryRepository;
    @Mock ProductOutboxEventPublisher outboxEventPublisher;

    private final Category category = ProductFixture.categoryWithId();

    // ── create ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create: 정상 요청이면 Product와 Inventory를 저장하고 DTO를 반환한다")
    void create_success_savesBothAndReturnsDto() {
        CreateProductCommand command = new CreateProductCommand(
                ProductFixture.DEFAULT_CATEGORY_ID,
                ProductFixture.DEFAULT_PRODUCT_NAME,
                ProductFixture.DEFAULT_DESCRIPTION,
                ProductFixture.DEFAULT_PRICE,
                ProductFixture.DEFAULT_IMAGE_URL,
                ProductFixture.DEFAULT_STOCK);
        given(categoryRepository.findById(command.categoryId())).willReturn(Optional.of(category));
        given(productRepository.save(any(Product.class))).willAnswer(inv -> inv.getArgument(0));
        given(inventoryRepository.save(any(Inventory.class))).willAnswer(inv -> inv.getArgument(0));

        ProductDetailDto result = productCommandService.create(command);

        assertThat(result.name()).isEqualTo(ProductFixture.DEFAULT_PRODUCT_NAME);
        assertThat(result.stock()).isEqualTo(ProductFixture.DEFAULT_STOCK);
        assertThat(result.status()).isEqualTo("ON_SALE");
        then(productRepository).should().save(any(Product.class));
        then(inventoryRepository).should().save(any(Inventory.class));
        // strangler-2: product.updated 발행 (CQRS ⑤)
        then(outboxEventPublisher).should().publishProductUpdated(any(Product.class), anyInt());
    }

    @Test
    @DisplayName("create: 카테고리가 없으면 PRD-003 예외가 발생한다")
    void create_categoryNotFound_throwsPRD003() {
        CreateProductCommand command = new CreateProductCommand(99L, "이름", null, 1000L, null, 10);
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> productCommandService.create(command))
                .isInstanceOf(ProductException.class)
                .extracting(e -> ((ProductException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRD_003);
    }

    // ── update ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update: 정상 요청이면 상품 정보가 변경된 DTO를 반환한다")
    void update_success_returnsUpdatedDto() {
        Product product = ProductFixture.productWithId(category);
        UpdateProductCommand command = new UpdateProductCommand(
                ProductFixture.DEFAULT_CATEGORY_ID, "새이름", "새설명", 2000L, null);
        given(productRepository.findById(ProductFixture.DEFAULT_PRODUCT_ID)).willReturn(Optional.of(product));
        given(categoryRepository.findById(command.categoryId())).willReturn(Optional.of(category));
        given(inventoryRepository.findByProductId(ProductFixture.DEFAULT_PRODUCT_ID))
                .willReturn(Optional.of(ProductFixture.inventoryWithId(product)));

        ProductDetailDto result = productCommandService.update(ProductFixture.DEFAULT_PRODUCT_ID, command);

        assertThat(result.name()).isEqualTo("새이름");
        assertThat(result.price()).isEqualTo(2000L);
        then(outboxEventPublisher).should().publishProductUpdated(any(Product.class), anyInt());
    }

    @Test
    @DisplayName("update: 상품이 없으면 PRD-001 예외가 발생한다")
    void update_productNotFound_throwsPRD001() {
        UpdateProductCommand command = new UpdateProductCommand(1L, "이름", null, 1000L, null);
        given(productRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> productCommandService.update(99L, command))
                .isInstanceOf(ProductException.class)
                .extracting(e -> ((ProductException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRD_001);
    }

    @Test
    @DisplayName("update: 카테고리가 없으면 PRD-003 예외가 발생한다")
    void update_categoryNotFound_throwsPRD003() {
        Product product = ProductFixture.productWithId(category);
        UpdateProductCommand command = new UpdateProductCommand(99L, "이름", null, 1000L, null);
        given(productRepository.findById(ProductFixture.DEFAULT_PRODUCT_ID)).willReturn(Optional.of(product));
        given(categoryRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> productCommandService.update(ProductFixture.DEFAULT_PRODUCT_ID, command))
                .isInstanceOf(ProductException.class)
                .extracting(e -> ((ProductException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRD_003);
    }

    // ── delete ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete: 상품 상태가 DISCONTINUED로 변경된다")
    void delete_setsStatusToDiscontinued() {
        Product product = ProductFixture.productWithId(category);
        given(productRepository.findById(ProductFixture.DEFAULT_PRODUCT_ID)).willReturn(Optional.of(product));

        productCommandService.delete(ProductFixture.DEFAULT_PRODUCT_ID);

        assertThat(product.getStatus()).isEqualTo(ProductStatus.DISCONTINUED);
        then(outboxEventPublisher).should().publishProductUpdated(any(Product.class), anyInt());
    }

    @Test
    @DisplayName("delete: 상품이 없으면 PRD-001 예외가 발생한다")
    void delete_productNotFound_throwsPRD001() {
        given(productRepository.findById(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> productCommandService.delete(99L))
                .isInstanceOf(ProductException.class)
                .extracting(e -> ((ProductException) e).getErrorCode())
                .isEqualTo(ErrorCode.PRD_001);
    }
}
