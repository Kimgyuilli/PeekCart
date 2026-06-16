package com.peekcart.product.infrastructure;

import com.peekcart.product.domain.model.StockReservation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * {@link StockReservation} 엔티티에 대한 Spring Data JPA 리포지터리.
 */
public interface StockReservationJpaRepository extends JpaRepository<StockReservation, Long> {

    Optional<StockReservation> findByOrderId(Long orderId);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE StockReservation r SET r.status = com.peekcart.product.domain.model.ReservationStatus.RELEASED, "
            + "r.releasedAt = :now WHERE r.orderId = :orderId "
            + "AND r.status = com.peekcart.product.domain.model.ReservationStatus.RESERVED")
    int markReleasedIfReserved(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);
}
