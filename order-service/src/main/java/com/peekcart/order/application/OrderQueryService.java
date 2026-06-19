package com.peekcart.order.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.application.dto.OrderSummaryDto;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 조회를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class OrderQueryService {

    private final OrderRepository orderRepository;

    /**
     * 사용자의 주문 목록을 페이징으로 조회한다.
     * orderItems에 접근하지 않아 N+1 쿼리가 발생하지 않는다.
     */
    public Page<OrderSummaryDto> getOrders(Long userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable)
                .map(OrderSummaryDto::from);
    }

    /**
     * 주문 상세를 조회한다.
     *
     * @throws OrderException 주문이 없거나 본인 주문이 아니면 {@code ORD-001}
     */
    public OrderDetailDto getOrder(Long userId, Long orderId) {
        return orderRepository.findByIdAndUserId(orderId, userId)
                .map(OrderDetailDto::from)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
    }
}
