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

    /**
     * 상품 단가를 반환한다 (주문 시점 스냅샷, read-only).
     * <p>
     * 재고 차감은 더 이상 동기로 하지 않고 {@code order.created} → Product 예약 Saga 로 처리한다
     * (ADR-0010 F2 · ADR-0012 D3). 단가는 strangler-2 에서 {@code product.updated} 로컬 캐시로 대체 예정.
     *
     * @param productId 상품 PK
     * @return 상품 단가
     * @throws RuntimeException 상품 미존재 시 예외
     */
    long getUnitPrice(Long productId);
}
