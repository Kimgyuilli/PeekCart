package com.peekcart.product.infrastructure.adapter;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.port.ProductPort;
import com.peekcart.product.domain.exception.ProductException;
import com.peekcart.product.domain.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link ProductPort}의 구현체. Product 도메인 내부를 캡슐화하여
 * Order 도메인이 Product 세부사항에 직접 의존하지 않도록 한다.
 * <p>
 * 재고 차감/복구는 예약 Saga 로(ADR-0012 D3), 단가는 product.updated 로컬 캐시로(strangler-2) 이관됐다.
 * 남은 동기 메서드는 장바구니 검증용 {@code verifyProductExists} 뿐이다.
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
}
