package com.peekcart.order.domain.repository;

import com.peekcart.order.domain.model.Order;
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
    /**
     * 사용자의 주문 목록 첫 페이지를 {@code (orderedAt, id)} 내림차순으로 조회한다.
     * {@code limit} 은 {@code size + 1} 이며, 초과분 1건이 다음 페이지 존재 여부의 근거다.
     */
    List<Order> findFirstPage(Long userId, Pageable limit);

    /**
     * 커서 위치보다 뒤(더 과거)의 주문을 같은 정렬로 조회한다.
     * {@code orderedAt} 이 유니크하지 않으므로 동률에서는 {@code id} 로 끊는다 —
     * 이 tie-break 가 없으면 동일 시각 주문이 페이지 경계에서 누락되거나 중복된다.
     */
    List<Order> findPageAfterCursor(Long userId, LocalDateTime orderedAt, Long id, Pageable limit);

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

    /**
     * 예약 lease 가 만료됐는데 아직 결제를 시작하지 않은 PENDING 주문을 조회한다.
     * 기존 두 조회가 비우지 못한 구간 — {@code reservationConfirmedAt} 이 채워졌고(예약 확정 완료)
     * {@code PAYMENT_REQUESTED} 로도 넘어가지 않은 주문 — 의 수명 상한이다. 이 상한이 없으면
     * Product 의 lease sweeper 가 살아있는 주문의 재고를 회수해 oversell 이 된다.
     */
    List<Order> findExpiredReservationLease(LocalDateTime now);
}
