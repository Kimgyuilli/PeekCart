package com.peekcart.product.infrastructure;

import com.peekcart.product.domain.model.StockReservation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link StockReservation} 엔티티에 대한 Spring Data JPA 리포지터리.
 */
public interface StockReservationJpaRepository extends JpaRepository<StockReservation, Long> {

    Optional<StockReservation> findByOrderId(Long orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM StockReservation r WHERE r.orderId = :orderId")
    Optional<StockReservation> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE stock_reservations SET status = 'APPROVAL_HELD', approval_fenced = TRUE "
            + "WHERE order_id = :orderId AND status = 'RESERVED' AND expires_at > :now", nativeQuery = true)
    int holdForApproval(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE stock_reservations SET status = 'RELEASED', released_at = :now "
            + "WHERE order_id = :orderId AND status IN ('RESERVED', 'APPROVAL_HELD')", nativeQuery = true)
    int releaseAfterPaymentFailed(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE stock_reservations SET status = 'RELEASED', released_at = :now "
            + "WHERE order_id = :orderId AND approval_fenced = TRUE AND refund_result = 'SUCCEEDED' "
            + "AND status IN ('APPROVAL_HELD', 'CONFIRMED')", nativeQuery = true)
    int releaseAfterRefund(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE StockReservation r SET r.status = com.peekcart.product.domain.model.ReservationStatus.RELEASED, "
            + "r.releasedAt = :now WHERE r.orderId = :orderId "
            + "AND r.status = com.peekcart.product.domain.model.ReservationStatus.RESERVED")
    int markReleasedIfReserved(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE StockReservation r SET r.status = com.peekcart.product.domain.model.ReservationStatus.CONFIRMED, "
            + "r.confirmedAt = :now WHERE r.orderId = :orderId "
            + "AND r.status IN (com.peekcart.product.domain.model.ReservationStatus.RESERVED, "
            + "com.peekcart.product.domain.model.ReservationStatus.APPROVAL_HELD)")
    int markConfirmedIfReserved(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE StockReservation r SET r.compensatedAt = :now "
            + "WHERE r.orderId = :orderId AND r.compensatedAt IS NULL")
    int markCompensatedIfAbsent(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    @Query("SELECT r FROM StockReservation r "
            + "WHERE r.status = com.peekcart.product.domain.model.ReservationStatus.RESERVED "
            + "AND r.expiresAt IS NOT NULL AND r.expiresAt < :cutoff ORDER BY r.expiresAt ASC")
    List<StockReservation> findExpiredReserved(@Param("cutoff") LocalDateTime cutoff, Pageable pageable);
}
