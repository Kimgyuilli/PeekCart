package com.peekcart.order.domain.repository;

import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 주문 도메인 리포지터리 인터페이스.
 */
public interface OrderRepository {
    Order save(Order order);
    Optional<Order> findById(Long id);
    Optional<Order> findByIdAndUserId(Long id, Long userId);
    Page<Order> findByUserId(Long userId, Pageable pageable);
    List<Order> findByStatusAndOrderedAtBefore(OrderStatus status, LocalDateTime cutoff);

    /**
     * 예약이 확정되지 않은 채 마감 시각을 넘긴 PENDING 주문을 조회한다 (예약 미도착 수렴용).
     * 정상 예약 진행 중(확정됨) 주문의 조기 취소를 막는다.
     */
    List<Order> findUnconfirmedReservationBefore(LocalDateTime cutoff);
}
