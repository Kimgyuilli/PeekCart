package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.Order;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdAndUserId(Long id, Long userId);

    /**
     * replay 적격성 판정용 <b>비관적 잠금</b> 조회 (④-c-2b-4a P19 · 계획 리뷰 3R #6).
     *
     * <p>잠그지 않으면 판정과 claim 사이에 주문 상태가 바뀐다 — {@code PENDING} 이라 allow 된 직후
     * 취소가 커밋되면 정책이 막으려던 재적용이 그대로 일어난다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);

    @Query("SELECT o FROM Order o WHERE o.userId = :userId ORDER BY o.orderedAt DESC, o.id DESC")
    List<Order> findFirstPageByUserId(@Param("userId") Long userId, Pageable limit);

    @Query("SELECT o FROM Order o WHERE o.userId = :userId "
            + "AND (o.orderedAt < :orderedAt OR (o.orderedAt = :orderedAt AND o.id < :id)) "
            + "ORDER BY o.orderedAt DESC, o.id DESC")
    List<Order> findPageByUserIdAfterCursor(@Param("userId") Long userId,
                                            @Param("orderedAt") LocalDateTime orderedAt,
                                            @Param("id") Long id,
                                            Pageable limit);

    @Query("SELECT o FROM Order o JOIN FETCH o.orderItems "
            + "WHERE o.status = com.peekcart.order.domain.model.OrderStatus.PAYMENT_REQUESTED "
            + "AND (o.paymentRequestedAt < :cutoff OR (o.paymentRequestedAt IS NULL AND o.orderedAt < :cutoff))")
    List<Order> findExpiredPaymentRequested(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT o FROM Order o WHERE o.status = com.peekcart.order.domain.model.OrderStatus.PENDING "
            + "AND o.reservationConfirmedAt IS NULL AND o.orderedAt < :cutoff")
    List<Order> findUnconfirmedReservationBefore(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT o FROM Order o WHERE o.status = com.peekcart.order.domain.model.OrderStatus.PENDING "
            + "AND o.reservationExpiresAt IS NOT NULL AND o.reservationExpiresAt < :now")
    List<Order> findExpiredReservationLease(@Param("now") LocalDateTime now);
}
