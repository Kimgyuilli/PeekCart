package com.peekcart.order.domain.repository;

import com.peekcart.order.domain.model.CompensationReason;
import com.peekcart.order.domain.model.CompensationStatus;
import com.peekcart.order.domain.model.OrderCompensation;

import java.util.Optional;

/**
 * 주문 보상 원장 리포지터리.
 */
public interface OrderCompensationRepository {

    OrderCompensation save(OrderCompensation compensation);

    Optional<OrderCompensation> findByOrderIdAndReason(Long orderId, CompensationReason reason);

    /**
     * 상태별 원장 건수 (보상 backlog Gauge 용).
     *
     * <p>누적 Counter 로는 "지금 몇 건이 미해소인가" 를 알 수 없어 alert 를 만들 데이터가 없다.
     */
    long countByStatus(CompensationStatus status);
}
