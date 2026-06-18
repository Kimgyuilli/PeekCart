package com.peekcart.support.fixture;

import com.peekcart.product.application.dto.ProductDetailDto;
import com.peekcart.product.application.dto.ProductInfoDto;
import com.peekcart.product.application.dto.ProductListDto;
import com.peekcart.product.domain.model.Category;
import com.peekcart.product.domain.model.Inventory;
import com.peekcart.product.domain.model.Product;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Product 도메인 테스트 픽스처 팩토리.
 */
public class ProductFixture {

    public static final Long DEFAULT_CATEGORY_ID = 1L;
    public static final String DEFAULT_CATEGORY_NAME = "전자기기";
    public static final Long DEFAULT_PRODUCT_ID = 1L;
    public static final String DEFAULT_PRODUCT_NAME = "스마트폰";
    public static final String DEFAULT_DESCRIPTION = "최신 스마트폰";
    public static final long DEFAULT_PRICE = 1_000_000L;
    public static final String DEFAULT_IMAGE_URL = "https://example.com/phone.jpg";
    public static final int DEFAULT_STOCK = 100;

    private ProductFixture() {}

    public static Category category() {
        return Category.create(DEFAULT_CATEGORY_NAME, null);
    }

    public static Category categoryWithId() {
        Category category = category();
        ReflectionTestUtils.setField(category, "id", DEFAULT_CATEGORY_ID);
        return category;
    }

    public static Product product(Category category) {
        return Product.create(category, DEFAULT_PRODUCT_NAME, DEFAULT_DESCRIPTION, DEFAULT_PRICE, DEFAULT_IMAGE_URL);
    }

    public static Product productWithId(Category category) {
        Product product = product(category);
        ReflectionTestUtils.setField(product, "id", DEFAULT_PRODUCT_ID);
        return product;
    }

    public static Inventory inventory(Product product) {
        return Inventory.create(product, DEFAULT_STOCK);
    }

    public static Inventory inventoryWithId(Product product) {
        Inventory inventory = inventory(product);
        ReflectionTestUtils.setField(inventory, "id", 1L);
        return inventory;
    }

    public static ProductDetailDto detailDto() {
        return new ProductDetailDto(
                DEFAULT_PRODUCT_ID,
                DEFAULT_CATEGORY_ID,
                DEFAULT_CATEGORY_NAME,
                DEFAULT_PRODUCT_NAME,
                DEFAULT_DESCRIPTION,
                DEFAULT_PRICE,
                DEFAULT_IMAGE_URL,
                "ON_SALE",
                DEFAULT_STOCK
        );
    }

    public static ProductInfoDto productInfoDto() {
        return new ProductInfoDto(
                DEFAULT_PRODUCT_ID,
                DEFAULT_CATEGORY_ID,
                DEFAULT_CATEGORY_NAME,
                DEFAULT_PRODUCT_NAME,
                DEFAULT_DESCRIPTION,
                DEFAULT_PRICE,
                DEFAULT_IMAGE_URL,
                "ON_SALE"
        );
    }

    public static ProductListDto productListDto() {
        return new ProductListDto(
                DEFAULT_PRODUCT_ID,
                DEFAULT_PRODUCT_NAME,
                DEFAULT_PRICE,
                DEFAULT_IMAGE_URL,
                "ON_SALE"
        );
    }
}
