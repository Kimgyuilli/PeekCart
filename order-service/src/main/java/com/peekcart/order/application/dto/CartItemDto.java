package com.peekcart.order.application.dto;

import com.peekcart.order.domain.model.CartItem;
import com.peekcart.order.domain.model.ProductPriceCache;

/** Product metadata is eventually consistent; null availability means unknown, not unavailable. */
public record CartItemDto(Long id, Long productId, int quantity, String productName,
                          Long unitPrice, String productStatus, Boolean available) {

    public static CartItemDto from(CartItem item) {
        return from(item, null);
    }

    public static CartItemDto from(CartItem item, ProductPriceCache product) {
        String status = product == null ? null : product.getProductStatus();
        Boolean available = switch (status == null ? "" : status) {
            case "ACTIVE" -> true;
            case "INACTIVE", "SOLD_OUT" -> false;
            default -> null;
        };
        return new CartItemDto(item.getId(), item.getProductId(), item.getQuantity(),
                product == null ? null : product.getProductName(),
                product == null ? null : product.getUnitPrice(), status, available);
    }
}
