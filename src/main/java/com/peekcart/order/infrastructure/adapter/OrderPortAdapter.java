package com.peekcart.order.infrastructure.adapter;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.payment.application.port.OrderPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * {@link OrderPort}의 구현체. Order 도메인 내부를 캡슐화하여
 * Payment 도메인이 Order 세부사항에 직접 의존하지 않도록 한다.
 */
@Component
@RequiredArgsConstructor
public class OrderPortAdapter implements OrderPort {

    private final OrderRepository orderRepository;

    @Override
    public void transitionToPaymentRequested(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
        order.markPaymentRequested();
    }

    @Override
    public void verifyOrderOwner(Long userId, Long orderId) {
        orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
    }
}
