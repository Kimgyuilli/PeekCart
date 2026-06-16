package com.peekcart.global.outbox.dto;

import java.time.LocalDateTime;

/**
 * {@code product.updated} 이벤트 payload (ADR-0012:48, CQRS ⑤).
 * <p>
 * ADR-0012:48 확정 필수 필드({@code productId, name, price, availableStock, status, categoryId, updatedAt})
 * 전체를 발행한다. {@code status} 는 ADR 계약 값({@code ACTIVE/INACTIVE/SOLD_OUT})으로 매핑된 문자열이다.
 * <p>
 * {@code version} 은 순서 판정용 additive 필드(ADR §46 하위호환 필드 추가 허용)다. Product {@code @Version}
 * 기반 per-product monotonic 값으로, 구독자가 stale-skip({@code source_version < version}) 에 사용한다.
 */
public record ProductUpdatedPayload(
        Long productId,
        String name,
        long price,
        int availableStock,
        String status,
        Long categoryId,
        LocalDateTime updatedAt,
        long version
) {
}
