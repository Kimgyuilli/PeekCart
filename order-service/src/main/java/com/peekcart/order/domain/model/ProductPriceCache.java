package com.peekcart.order.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Order 로컬 가격 캐시 read model (CQRS ⑤, strangler-2).
 * <p>
 * {@code product.updated} 이벤트를 구독해 단가를 eventually-consistent 하게 적재한다.
 * 주문 생성 시 Product 동기 호출 없이 이 캐시에서 단가 스냅샷을 읽는다.
 * {@code sourceVersion} 은 마지막 적용 이벤트의 Product version 으로, 역순/replay 이벤트의
 * 과거 version 덮어쓰기를 막는 stale-skip 기준이다.
 */
@Entity
@Table(name = "product_price_cache")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProductPriceCache {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(name = "unit_price", nullable = false)
    private long unitPrice;

    @Column(name = "source_version", nullable = false)
    private long sourceVersion;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
