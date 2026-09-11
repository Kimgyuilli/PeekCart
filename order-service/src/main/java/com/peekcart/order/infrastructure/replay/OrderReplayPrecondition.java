package com.peekcart.order.infrastructure.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.deadletter.ReplayPreconditionPort;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.infrastructure.OrderJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * order 원장의 replay 사전조건 (구현 ④-c-2b-4a P19 · 계획 리뷰 2R #3·#4).
 *
 * <p>대상은 {@code stock.reservation.result} 하나다 — 이 토픽만 정책이 {@code preconditionRequired} 다.
 *
 * <h2>판정표가 소비 코드에서 유도된다</h2>
 * {@code OrderEventConsumer#handleStockReservationResult} 는 {@code reserved=true} 면 <b>상태 검사 없이</b>
 * {@code confirmReservation} 을 기록하고, {@code reserved=false} 면 {@code CANCELLED} 만 no-op 하고
 * 나머지는 {@code cancel()} 을 호출한다. 즉 <b>막아야 할 쪽은 소비 코드가 아니라 정책</b>이다.
 *
 * <p>{@link OrderStatus} <b>8종을 전부</b> 덮는다(total function) — 표에 없는 상태가 생기면
 * {@code UNKNOWN} 분기로 거부되고, 그 사실이 테스트에서 드러난다.
 */
@Component
@RequiredArgsConstructor
public class OrderReplayPrecondition implements ReplayPreconditionPort {

    private static final String STOCK_RESERVATION_RESULT = "stock.reservation.result";

    /** {@code reserved=false} 에서 {@code cancel()} 이 전이 가능한 상태. 그 외는 예외 → 재-DLQ 가 된다. */
    private static final Set<OrderStatus> CANCELLABLE =
            Set.of(OrderStatus.PENDING, OrderStatus.PAYMENT_REQUESTED, OrderStatus.PAYMENT_FAILED);

    private final OrderJpaRepository orderJpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String reject(String topic, String payload) {
        if (!STOCK_RESERVATION_RESULT.equals(topic)) {
            // 사전조건이 선언되지 않은 토픽이 여기 오면 배선 오류다. fail-closed.
            return "사전조건 판정 대상이 아닌 토픽이다 — " + topic;
        }

        JsonNode body;
        try {
            body = objectMapper.readTree(payload).path("payload");
        } catch (Exception e) {
            return "payload 를 파싱하지 못했다: " + e.getMessage();
        }

        JsonNode orderIdNode = body.get("orderId");
        JsonNode reservedNode = body.get("reserved");
        if (orderIdNode == null || !orderIdNode.canConvertToLong()
                || reservedNode == null || !reservedNode.isBoolean()) {
            // 소비 코드는 get(...) 직접 접근이라 부재 시 NPE 로 터진다. 그 실패를 replay 로 재생산하지 않는다.
            return "payload 의 orderId/reserved 가 없거나 타입이 다르다";
        }

        long orderId = orderIdNode.asLong();
        boolean reserved = reservedNode.asBoolean();

        Optional<Order> found = orderJpaRepository.findByIdForUpdate(orderId);
        if (found.isEmpty()) {
            return "주문이 없다 — orderId=" + orderId;
        }
        Order order = found.get();

        if (reserved) {
            if (order.getStatus() != OrderStatus.PENDING) {
                return "예약 확정을 재적용할 수 없는 상태다 — orderId=" + orderId + ", status=" + order.getStatus();
            }
            if (order.getReservationConfirmedAt() != null) {
                return "이미 예약이 확정됐다 — 재적용이 확정 시각을 되돌린다. orderId=" + orderId;
            }
            return null;
        }

        if (order.getStatus() == OrderStatus.CANCELLED) {
            // 소비 측이 이미 no-op 한다.
            return null;
        }
        if (!CANCELLABLE.contains(order.getStatus())) {
            return "취소로 전이할 수 없는 상태다 — 소비 코드가 cancel() 을 호출해 예외 → 재-DLQ 가 된다. "
                    + "orderId=" + orderId + ", status=" + order.getStatus();
        }
        return null;
    }
}
