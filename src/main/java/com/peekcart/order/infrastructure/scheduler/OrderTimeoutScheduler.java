package com.peekcart.order.infrastructure.scheduler;

import com.peekcart.order.application.OrderCommandService;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 타임아웃 주문을 자동 취소하는 스케줄러.
 * <ul>
 *   <li>PAYMENT_REQUESTED 가 15분 초과된 주문</li>
 *   <li>재고 예약 결과 미도착으로 확정되지 않은 채 5분 초과된 PENDING 주문 (예약 Saga 수렴, ADR-0012 D3)</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderTimeoutScheduler {

    private final OrderRepository orderRepository;
    private final OrderCommandService orderCommandService;

    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "orderTimeoutCancelJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void cancelExpiredOrders() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(15);
        List<Order> expiredOrders = orderRepository.findExpiredPaymentRequested(cutoff);

        for (Order order : expiredOrders) {
            cancelSafely(order.getId(), order.getOrderNumber());
        }
    }

    /**
     * 예약 미확정 PENDING 주문 수렴. 정상 예약 진행 중(확정됨) 주문은 제외되어 조기 취소되지 않는다.
     */
    @Scheduled(fixedDelay = 60_000)
    @SchedulerLock(name = "orderReservationTimeoutJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void cancelUnconfirmedReservations() {
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(5);
        List<Order> stuck = orderRepository.findUnconfirmedReservationBefore(cutoff);

        for (Order order : stuck) {
            cancelSafely(order.getId(), order.getOrderNumber());
        }
    }

    private void cancelSafely(Long orderId, String orderNumber) {
        try {
            orderCommandService.cancelExpiredOrder(orderId);
            log.info("타임아웃 주문 취소: orderId={}, orderNumber={}", orderId, orderNumber);
        } catch (OrderException e) {
            log.warn("타임아웃 주문 취소 스킵 (상태 경합): orderId={}, reason={}", orderId, e.getMessage());
        } catch (Exception e) {
            log.error("타임아웃 주문 취소 실패: orderId={}", orderId, e);
        }
    }
}
