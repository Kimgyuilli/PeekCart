package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface OrderJpaRepository extends JpaRepository<Order, Long> {
    Optional<Order> findByIdAndUserId(Long id, Long userId);
    Page<Order> findByUserId(Long userId, Pageable pageable);

    @Query("SELECT o FROM Order o JOIN FETCH o.orderItems "
            + "WHERE o.status = com.peekcart.order.domain.model.OrderStatus.PAYMENT_REQUESTED "
            + "AND (o.paymentRequestedAt < :cutoff OR (o.paymentRequestedAt IS NULL AND o.orderedAt < :cutoff))")
    List<Order> findExpiredPaymentRequested(@Param("cutoff") LocalDateTime cutoff);

    @Query("SELECT o FROM Order o WHERE o.status = com.peekcart.order.domain.model.OrderStatus.PENDING "
            + "AND o.reservationConfirmedAt IS NULL AND o.orderedAt < :cutoff")
    List<Order> findUnconfirmedReservationBefore(@Param("cutoff") LocalDateTime cutoff);
}
