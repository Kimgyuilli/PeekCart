package com.peekcart.order.application;

import com.peekcart.order.application.dto.CartDetailDto;
import com.peekcart.order.application.dto.CartItemDto;
import com.peekcart.order.domain.model.Cart;
import com.peekcart.order.domain.model.ProductPriceCache;
import com.peekcart.order.domain.repository.ProductPriceCacheRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Composes all cart responses from a single local read-model query (ADR-0036). */
@Component
@RequiredArgsConstructor
public class CartDetailReader {
    private final ProductPriceCacheRepository priceCacheRepository;

    public CartDetailDto read(Cart cart) {
        if (cart.getItems().isEmpty()) {
            return new CartDetailDto(cart.getId(), java.util.List.of());
        }
        var ids = cart.getItems().stream().map(item -> item.getProductId()).distinct().toList();
        Map<Long, ProductPriceCache> products = priceCacheRepository.findAllByProductIdIn(ids).stream()
                .collect(Collectors.toMap(ProductPriceCache::getProductId, Function.identity()));
        return new CartDetailDto(cart.getId(), cart.getItems().stream()
                .map(item -> CartItemDto.from(item, products.get(item.getProductId()))).toList());
    }
}
