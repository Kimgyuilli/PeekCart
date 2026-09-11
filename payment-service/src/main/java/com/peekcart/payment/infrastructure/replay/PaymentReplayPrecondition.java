package com.peekcart.payment.infrastructure.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.deadletter.ReplayPreconditionPort;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.infrastructure.PaymentJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * payment 원장의 replay 사전조건 (구현 ④-c-2b-4a P19 · 계획 리뷰 2R #3 · 3R #4).
 *
 * <p><b>order 판정을 재사용할 수 없다</b> — DB-per-service 라 payment 는 Order 상태를 볼 수 없고,
 * 소비 경로도 다르다({@code Payment#markReadyForPayment}). 그래서 정책 키가
 * {@code (원장 소유 서비스, 토픽)} 이고 판정기도 서비스별이다.
 *
 * <p>{@link PaymentStatus} 5종 × {@code readyForPayment} 2종을 전부 덮는다(total function).
 * {@code markReadyForPayment} 는 <b>상태 가드가 없어</b> terminal 상태에서도 flag·lease 를 덮으므로,
 * 막는 책임이 정책에 있다.
 */
@Component
@RequiredArgsConstructor
public class PaymentReplayPrecondition implements ReplayPreconditionPort {

    private static final String STOCK_RESERVATION_RESULT = "stock.reservation.result";

    private final PaymentJpaRepository paymentJpaRepository;
    private final ObjectMapper objectMapper;

    @Override
    public String reject(String topic, String payload) {
        if (!STOCK_RESERVATION_RESULT.equals(topic)) {
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
            return "payload 의 orderId/reserved 가 없거나 타입이 다르다";
        }

        if (!reservedNode.asBoolean()) {
            // 소비 코드가 reserved=false 를 즉시 return 한다 — 재전달이 무해하다.
            return null;
        }

        long orderId = orderIdNode.asLong();
        Optional<Payment> found = paymentJpaRepository.findByOrderIdForUpdate(orderId);
        if (found.isEmpty()) {
            return "결제 건이 없다 — orderId=" + orderId;
        }
        Payment payment = found.get();

        if (payment.getStatus() != PaymentStatus.PENDING) {
            return "결제가 이미 진행/종결된 상태다 — 재적용이 lease 를 덮는다. orderId=" + orderId
                    + ", status=" + payment.getStatus();
        }
        if (payment.isReadyForPayment()) {
            return "이미 결제 준비가 완료됐다 — 재적용이 예약 만료 판정을 되돌린다. orderId=" + orderId;
        }
        return null;
    }
}
