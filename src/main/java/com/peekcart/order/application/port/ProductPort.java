package com.peekcart.order.application.port;

/**
 * Order 도메인이 Product 도메인에 요청하는 오퍼레이션을 정의한다.
 * 구현체는 Product infrastructure 레이어에 위치한다.
 */
public interface ProductPort {

    /**
     * 상품 존재 여부를 검증한다.
     *
     * @param productId 상품 PK
     * @throws RuntimeException 상품이 존재하지 않으면 예외
     */
    void verifyProductExists(Long productId);
}
