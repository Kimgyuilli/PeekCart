package com.peekcart.order.domain.repository;

import com.peekcart.order.domain.model.Order;
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

    /**
     * 결제 요청 후 마감 시각을 넘긴 PAYMENT_REQUESTED 주문을 조회한다 (결제 타임아웃 수렴용).
     * 기준은 {@code paymentRequestedAt} — 주문 생성이 아닌 결제 요청 시점이라, 생성 후 오래 지나
     * 결제를 시작한 주문이 진행 중 취소되는 race 를 막는다. 마이그레이션 직후 {@code paymentRequestedAt} 이
     * 비어있는 기존 행은 {@code orderedAt} 으로 폴백해 누락(영구 미취소) 을 방지한다.
     */
    List<Order> findExpiredPaymentRequested(LocalDateTime cutoff);

    /**
     * 예약이 확정되지 않은 채 마감 시각을 넘긴 PENDING 주문을 조회한다 (예약 미도착 수렴용).
     * 정상 예약 진행 중(확정됨) 주문의 조기 취소를 막는다.
     */
    List<Order> findUnconfirmedReservationBefore(LocalDateTime cutoff);
}
