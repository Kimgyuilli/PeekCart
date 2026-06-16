package com.peekcart.product.infrastructure.adapter;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.port.ProductPort;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.model.Product;
import com.peekcart.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ProductPort}의 구현체. Product 도메인 내부를 캡슐화하여
 * Order 도메인이 Product 세부사항에 직접 의존하지 않도록 한다.
 * <p>
 * 재고 차감/복구는 더 이상 동기로 노출하지 않는다 (예약 Saga 로 이관, ADR-0012 D3).
 * 남은 read-only 메서드는 strangler-2 에서 로컬 캐시로 대체 예정.
 */
@Component
@RequiredArgsConstructor
public class ProductPortAdapter implements ProductPort {

    private final ProductRepository productRepository;

    @Override
    public void verifyProductExists(Long productId) {
        productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
    }

    @Override
    public long getUnitPrice(Long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ProductException(ErrorCode.PRD_001));
        return product.getPrice();
    }
}
