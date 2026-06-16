package com.peekcart.order.infrastructure;

import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * {@link OrderRepository}의 JPA 구현체.
 */
@Repository
@RequiredArgsConstructor
public class OrderRepositoryImpl implements OrderRepository {

    private final OrderJpaRepository orderJpaRepository;

    @Override
    public Order save(Order order) {
        return orderJpaRepository.save(order);
    }

    @Override
    public Optional<Order> findById(Long id) {
        return orderJpaRepository.findById(id);
    }

    @Override
    public Optional<Order> findByIdAndUserId(Long id, Long userId) {
        return orderJpaRepository.findByIdAndUserId(id, userId);
    }

    @Override
    public Page<Order> findByUserId(Long userId, Pageable pageable) {
        return orderJpaRepository.findByUserId(userId, pageable);
    }

    @Override
    public List<Order> findByStatusAndOrderedAtBefore(OrderStatus status, LocalDateTime cutoff) {
        return orderJpaRepository.findByStatusAndOrderedAtBefore(status, cutoff);
    }

    @Override
    public List<Order> findUnconfirmedReservationBefore(LocalDateTime cutoff) {
        return orderJpaRepository.findUnconfirmedReservationBefore(cutoff);
    }
}
