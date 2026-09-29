package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.global.outbox.dto.CompensationReason;
import com.peekcart.payment.application.PaymentRefundService;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 다른 서비스가 감지한 환불 보상 요청을 소비하는 Consumer (ADR-0018 D1/D3).
 *
 * <p>두 토픽과 Payment 로컬 감지({@link PaymentEventConsumer#handleOrderCancelled})가
 * <b>같은 fence 로 수렴</b>한다 — 세 진입점 모두 {@code payment_refunds.order_id} UNIQUE 에
 * 삽입만 시도하고, 중복은 예외가 아니라 정상 no-op 이다. 따라서 요청 2종의 도착 순서·중복에
 * 의존하는 로직이 여기에 없다(ADR-0018 D1 cross-topic 순서 무보장).
 *
 * <p><b>PG 를 호출하지 않는다.</b> 소비 트랜잭션 안에서 외부를 부르면 성공 후 롤백 시 fence 행이
 * 사라져 다음 트리거가 다시 호출한다 — 실행자는 dispatcher 하나뿐이다(ADR-0018 D3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CompensationRequestConsumer {

    private static final String GROUP_STOCK_COMPENSATION =
            "payment-svc-stock-compensation-requested-group";
    private static final String GROUP_ORDER_COMPENSATION =
            "payment-svc-order-compensation-requested-group";

    private final PaymentRepository paymentRepository;
    private final PaymentRefundService paymentRefundService;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;

    /** Product 가 감지한 {@code PAID_BUT_UNRESERVED} 보상 요청. */
    @KafkaListener(topics = "stock.compensation.requested", groupId = GROUP_STOCK_COMPENSATION)
    @Transactional
    public void handleStockCompensationRequested(String message) {
        consume(message, GROUP_STOCK_COMPENSATION);
    }

    /** Order 가 감지한 {@code PAID_BUT_CANCELLED} 보상 요청. */
    @KafkaListener(topics = "order.compensation.requested", groupId = GROUP_ORDER_COMPENSATION)
    @Transactional
    public void handleOrderCompensationRequested(String message) {
        consume(message, GROUP_ORDER_COMPENSATION);
    }

    private void consume(String message, String consumerGroup) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, consumerGroup, () -> {
            Long orderId = requirePositiveLong(payload, "orderId");
            requirePresent(payload, "detectedAt");
            // 결제 미존재는 transient 로 본다 — Payment 의 order.created 소비가 지연되면 잠시 없을 수
            // 있다. bounded 재시도(1s/5s/30s) 후 DLQ 로 가며, 원장이 남지 않으므로 유실이 아니라
            // 미시작이다(DLQ 원장에는 남는다).
            Payment payment = paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new PaymentException(ErrorCode.PAY_003));
            boolean fenced = paymentRefundService.requestRefund(payment, readReason(payload));
            if (!fenced) {
                // fence 를 못 잡은 세 경우 모두 정상이다: 진행 중인 환불에 온 중복 트리거 ·
                // 이미 종결돼 회신이 재발행된 경우(늦은 감지 원장이 여기서 닫힌다) ·
                // 과금이 성립하지 않은 결제. 요청은 결제 승인 이후에만 발행되므로(감지 3지점 모두
                // payment.completed 이후) "아직 APPROVED 가 아니라 놓치는" 순서는 존재하지 않는다.
                log.debug("환불 요청 no-op — orderId={}, status={}", orderId, payment.getStatus());
            }
        });
    }

    /**
     * 사유를 읽는다. <b>필수 필드이므로 부재/null 은 거부</b>하고(ADR-0018 D1 · ADR-0012 D2 는 필수 필드
     * 삭제를 허용하지 않는다), <b>모르는 값만</b> {@code UNKNOWN} 으로 정규화한다.
     *
     * <p>둘을 같이 취급하면 안 된다 — 미지 값은 전방 호환(새 producer 가 추가한 사유)이지만, 필드
     * 부재는 잘못 생성된 메시지이고 그걸로 <b>금전 동작을 개시</b>하게 된다. 정규화는 payload 문자열이
     * 그대로 메트릭 태그가 되는 것도 막는다(카디널리티).
     */
    private String readReason(JsonNode payload) {
        String raw = requirePresent(payload, "reason").asText();
        for (CompensationReason known : CompensationReason.values()) {
            if (known.name().equals(raw)) {
                return raw;
            }
        }
        log.warn("알 수 없는 보상 사유 — 태그를 UNKNOWN 으로 정규화, reason={}", raw);
        return "UNKNOWN";
    }

    /**
     * 필수 필드를 읽는다. 부재/null 은 계약 위반이라 예외로 던져 재시도·DLQ 로 보낸다 —
     * 조용히 넘기면 불완전한 메시지가 환불을 시작시킨다.
     */
    private JsonNode requirePresent(JsonNode payload, String field) {
        JsonNode node = payload == null ? null : payload.get(field);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("환불 요청 필수 필드 누락: " + field);
        }
        return node;
    }

    /**
     * 양수 식별자를 읽는다. {@code asLong()} 은 null·문자열을 <b>0 으로 축약</b>하므로 타입까지 본다 —
     * orderId 0 으로 fence 를 만들면 존재하지 않는 주문의 환불 원장이 생긴다.
     */
    private Long requirePositiveLong(JsonNode payload, String field) {
        JsonNode node = requirePresent(payload, field);
        if (!node.canConvertToLong() || node.asLong() <= 0L) {
            throw new IllegalArgumentException("환불 요청 필드가 양수 식별자가 아님: " + field + "=" + node);
        }
        return node.asLong();
    }
}
