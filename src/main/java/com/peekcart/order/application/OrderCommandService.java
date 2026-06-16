package com.peekcart.order.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.order.application.dto.CreateOrderCommand;
import com.peekcart.order.application.dto.OrderDetailDto;
import com.peekcart.order.application.port.ProductPort;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Cart;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.repository.CartRepository;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.order.infrastructure.outbox.OrderOutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * 주문 생성/취소를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class OrderCommandService {

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final ProductPort productPort;
    private final OrderOutboxEventPublisher outboxEventPublisher;

    /**
     * 장바구니를 기반으로 주문을 생성하고 재고를 즉시 차감한다.
     *
     * @throws OrderException 장바구니가 없으면 {@code ORD-006}, 비어있으면 {@code ORD-004}
     */
    public OrderDetailDto createOrder(Long userId, CreateOrderCommand command) {
        Cart cart = cartRepository.findByUserId(userId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_006));

        if (cart.isEmpty()) {
            throw new OrderException(ErrorCode.ORD_004);
        }

        // 재고 차감은 동기로 하지 않고 order.created → Product 예약 Saga 로 처리한다 (ADR-0012 D3).
        // 단가만 스냅샷으로 읽는다 (strangler-2 에서 로컬 캐시로 대체).
        List<OrderItemData> itemDataList = cart.getItems().stream()
                .map(cartItem -> {
                    long unitPrice = productPort.getUnitPrice(cartItem.getProductId());
                    return new OrderItemData(cartItem.getProductId(), cartItem.getQuantity(), unitPrice);
                })
                .toList();

        Order order = Order.create(
                userId,
                generateOrderNumber(),
                command.receiverName(),
                command.phone(),
                command.zipcode(),
                command.address(),
                itemDataList
        );
        orderRepository.save(order);
        if (order.getId() != null) {
            MDC.put("orderId", order.getId().toString());
        }

        cart.clear();

        outboxEventPublisher.publishOrderCreated(order);

        return OrderDetailDto.from(order);
    }

    /**
     * 주문을 취소하고 재고를 복구한다.
     *
     * @throws OrderException 주문이 없으면 {@code ORD-001}, 취소 불가 상태면 {@code ORD-003}
     */
    public void cancelOrder(Long userId, Long orderId) {
        MDC.put("orderId", orderId.toString());
        Order order = orderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));

        order.cancel();

        // 재고 복구는 동기로 하지 않고 order.cancelled → Product release Saga 가 담당한다 (ADR-0012 D3).
        outboxEventPublisher.publishOrderCancelled(order);
    }

    /**
     * 타임아웃된 주문을 취소한다. 건별 독립 트랜잭션으로 처리한다.
     * 재고 복구는 order.cancelled → Product release Saga 가 담당한다.
     *
     * @throws OrderException 주문이 없으면 {@code ORD-001}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void cancelExpiredOrder(Long orderId) {
        MDC.put("orderId", orderId.toString());
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));

        order.cancel();

        outboxEventPublisher.publishOrderCancelled(order);
    }

    private String generateOrderNumber() {
        String date = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        return "ORD-" + date + "-" + suffix;
    }
}
