package com.peekcart.order.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.global.outbox.dto.OrderCancelReason;
import com.peekcart.global.port.SlackPort;
import com.peekcart.order.domain.exception.OrderException;
import com.peekcart.order.domain.model.CompensationReason;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderCompensation;
import com.peekcart.order.domain.model.OrderStatus;
import com.peekcart.order.domain.repository.OrderCompensationRepository;
import com.peekcart.order.infrastructure.metrics.OrderSagaMetrics;
import com.peekcart.order.domain.repository.OrderRepository;
import com.peekcart.order.infrastructure.outbox.OrderOutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 결제·재고예약 관련 Kafka 이벤트를 소비하여 주문 상태를 전이하는 Consumer.
 * <p>
 * 소비 토픽: {@code payment.requested}, {@code payment.completed}, {@code payment.failed},
 * {@code stock.reservation.result}, {@code payment.refunded}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventConsumer {

    private static final String GROUP_PAYMENT_REQUESTED = "order-svc-payment-requested-group";
    private static final String GROUP_PAYMENT_COMPLETED = "order-svc-payment-completed-group";
    private static final String GROUP_PAYMENT_FAILED = "order-svc-payment-failed-group";
    private static final String GROUP_STOCK_RESULT = "order-svc-stock-result-group";
    private static final String GROUP_PAYMENT_REFUNDED = "order-svc-payment-refunded-group";

    private final OrderRepository orderRepository;
    private final OrderCompensationRepository compensationRepository;
    private final OrderOutboxEventPublisher outboxEventPublisher;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;
    private final SlackPort slackPort;
    private final OrderSagaMetrics sagaMetrics;

    /**
     * 결제 시작(payment.requested)을 소비해 주문을 {@code PAYMENT_REQUESTED}로 전이한다
     * (동기 OrderPort.transitionToPaymentRequested 대체).
     * <p>예약 미확정 상태에서 선도착하면 ORD-008 로 DLQ 직행하는 대신 pending marker 를 기록해
     * {@code confirmReservation()} 시점에 수렴시킨다. 종료 상태(취소 등)면 no-op.
     */
    @KafkaListener(topics = "payment.requested", groupId = GROUP_PAYMENT_REQUESTED)
    @Transactional
    public void handlePaymentRequested(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_REQUESTED, () -> {
            Long orderId = payload.get("orderId").asLong();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            if (order.getStatus() != OrderStatus.PENDING) {
                log.debug("payment.requested 수신했으나 주문이 PENDING 아님 — no-op, orderId={}", orderId);
                return;
            }
            if (order.getReservationConfirmedAt() != null) {
                order.markPaymentRequested();
                log.debug("주문 상태 전이 → PAYMENT_REQUESTED — orderId={}", orderId);
            } else {
                order.markPaymentRequestedPending();
                log.debug("payment.requested 선도착(예약 미확정) — pending marker 기록, orderId={}", orderId);
            }
        });
    }

    /**
     * 결제 성공 시 주문 상태를 {@code PAYMENT_COMPLETED}로 전이한다.
     * <p>이미 취소된 주문에 결제 완료가 도착하면(취소가 낙관 락 경쟁에서 이긴 경우 등) {@code ORD-003}
     * 으로 던져 DLQ 로 보내지 않는다 — 재시도해도 상태는 되돌아오지 않아 영구 실패이며, 실제로 필요한 건
     * <b>환불 보상</b>이다(ADR-0012 D3 ④). 여기서는 보상 필요를 원장에 남기고,
     * 환불 요청은 그 원장을 입력으로 삼는 경로가 한다.
     */
    @KafkaListener(topics = "payment.completed", groupId = GROUP_PAYMENT_COMPLETED)
    @Transactional
    public void handlePaymentCompleted(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_COMPLETED, () -> {
            Long orderId = payload.get("orderId").asLong();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            if (order.getStatus() == OrderStatus.CANCELLED) {
                recordPaidButCancelled(orderId);
                return;
            }
            order.transitionTo(OrderStatus.PAYMENT_COMPLETED);
            log.debug("주문 상태 전이 → PAYMENT_COMPLETED — orderId={}", orderId);
        });
    }

    /**
     * 결제 실패 시 주문을 취소하고 {@code order.cancelled}(reason={@code PAYMENT_FAILED})를 발행한다.
     *
     * <p>발행 결정: {@code order.cancelled} 를 <b>모든 취소의 단일 lifecycle 이벤트</b>로 두고
     * ADR-0010 D3-4 의 명문 경로를 복원한다. 재고 복구 자체는 Product 가 {@code payment.failed} 를 직접
     * 소비해 이미 수행하므로(ADR-0012 D3 refine) 이 발행은 중복 트리거지만, 소비자별로 무해하다 —
     * Product 는 예약 원장 {@code RESERVED→RELEASED} CAS 라 두 번째 release 가 no-op 이고, Payment 는
     * 이미 {@code FAILED} 라 {@code cancelBeforePayment()} 가 no-op 이며, Notification 은 중복 알림을
     * 피하려고 {@code reason=PAYMENT_FAILED} 를 스킵한다(사유 필드가 있어야 성립하는 분기다).
     */
    @KafkaListener(topics = "payment.failed", groupId = GROUP_PAYMENT_FAILED)
    @Transactional
    public void handlePaymentFailed(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_FAILED, () -> {
            Long orderId = payload.get("orderId").asLong();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            if (order.getStatus() == OrderStatus.CANCELLED) {
                // 사용자·타임아웃·예약 실패 취소가 먼저 커밋된 뒤 payment.failed 가 도착하는 경합.
                // 목표 상태(CANCELLED)는 이미 달성됐으므로 ORD-002 로 던져 DLQ 로 보내지 않는다 —
                // 재시도해도 상태는 같고, 취소 발행은 선행 경로가 이미 했다(reserved=false 경로와 동일 규약).
                log.debug("결제 실패 수신했으나 주문이 이미 취소됨 — no-op, orderId={}", orderId);
                return;
            }
            order.cancel();
            outboxEventPublisher.publishOrderCancelled(order, OrderCancelReason.PAYMENT_FAILED);
            log.debug("결제 실패로 주문 취소 — orderId={}", orderId);
        });
    }

    /**
     * 재고 예약 결과를 소비한다 (ADR-0012 D3).
     * <ul>
     *   <li>{@code reserved=false} → 주문 취소(PENDING→CANCELLED) + {@code order.cancelled} 발행</li>
     *   <li>{@code reserved=true} → 예약 확정 시각 기록(타임아웃 조기취소 방지)</li>
     * </ul>
     */
    @KafkaListener(topics = "stock.reservation.result", groupId = GROUP_STOCK_RESULT)
    @Transactional
    public void handleStockReservationResult(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_STOCK_RESULT, () -> {
            Long orderId = payload.get("orderId").asLong();
            boolean reserved = payload.get("reserved").asBoolean();
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new OrderException(ErrorCode.ORD_001));
            if (reserved) {
                order.confirmReservation(readReservationExpiresAt(payload));
                log.debug("재고 예약 확정 — orderId={}", orderId);
            } else if (order.getStatus() == OrderStatus.CANCELLED) {
                // cancel-before-create: 주문이 이미 취소된 뒤 reserved=false 가 도착할 수 있다 → 멱등 no-op
                log.debug("예약 실패 수신했으나 주문이 이미 취소됨 — no-op, orderId={}", orderId);
            } else {
                order.cancel();
                outboxEventPublisher.publishOrderCancelled(order, OrderCancelReason.RESERVATION_FAILED);
                log.debug("재고 예약 실패로 주문 취소 — orderId={}", orderId);
            }
        });
    }

    /**
     * 환불 결과 회신을 소비해 보상 원장을 <b>종결</b>한다 (ADR-0018 D4). 이 경로가 없으면
     * {@code order_compensations} 가 {@code OPEN} 으로 쌓이기만 한다.
     *
     * <p>결과별 종착이 다르다: {@code SUCCEEDED} 는 {@code RESOLVED}("환불 완료"),
     * {@code FAILED} 는 {@code REFUND_FAILED}("닫혔지만 해결되지 않음"), {@code UNRESOLVED} 는
     * <b>전이하지 않는다</b> — Payment 가 확정한 뒤 다시 발행하므로 여기서 닫으면 거짓이 된다.
     * (Payment 는 UNRESOLVED 를 발행하지 않지만, 계약상 값이 존재하므로 방어한다.)
     *
     * <p>원장이 없는 회신은 정상이다 — 감지 지점이 3곳이라 Payment 로컬 감지나 Product 감지만으로
     * 시작된 환불의 회신이 Order 에도 도착한다. 예외로 던져 DLQ 로 보낼 이유가 없다.
     */
    @KafkaListener(topics = "payment.refunded", groupId = GROUP_PAYMENT_REFUNDED)
    @Transactional
    public void handlePaymentRefunded(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_PAYMENT_REFUNDED, () -> {
            Long orderId = payload.get("orderId").asLong();
            String result = readText(payload, "result");
            OrderCompensation compensation = compensationRepository
                    .findByOrderIdAndReason(orderId, CompensationReason.PAID_BUT_CANCELLED)
                    .orElse(null);
            if (compensation == null) {
                log.debug("환불 회신 수신했으나 Order 보상 원장 없음 — 다른 감지 지점발 환불, orderId={}", orderId);
                return;
            }
            LocalDateTime resolvedAt = readResolvedAt(payload);
            if ("SUCCEEDED".equals(result)) {
                compensation.resolveByRefund(resolvedAt);
                log.info("환불 성공 회신 — 보상 해소(RESOLVED), orderId={}", orderId);
            } else if ("FAILED".equals(result)) {
                compensation.failByRefund(readText(payload, "failureCode"), resolvedAt);
                log.error("환불 영구 실패 회신 — 보상 미해결 종착(REFUND_FAILED), orderId={}, code={}",
                        orderId, compensation.getFailureCode());
            } else {
                // UNRESOLVED · 모르는 값 · 필드 부재 — 전이하지 않는다. 확정 회신이 뒤따른다.
                log.warn("확정되지 않은 환불 회신 — 전이 없음, orderId={}, result={}", orderId, result);
            }
        });
    }

    /**
     * 취소된 주문에 도착한 결제 완료를 <b>영속</b> 보상 원장으로 남긴다.
     *
     * <p>소비와 같은 트랜잭션이라 {@code processed_events} 커밋과 원장 기록이 함께 성립하거나 함께
     * 롤백된다 — 알림만 남기면 order-service 의 no-op {@code SlackPort} 때문에 신호가 사라지고,
     * 이벤트는 이미 처리 완료로 봉인돼 환불 경로가 재소비할 수 없다. 원장은
     * {@code (orderId, reason)} 유니크로 멱등이므로 DLQ 재발행에도 1행이다.
     */
    private void recordPaidButCancelled(Long orderId) {
        boolean alreadyOpen = compensationRepository
                .findByOrderIdAndReason(orderId, CompensationReason.PAID_BUT_CANCELLED).isPresent();
        if (!alreadyOpen) {
            OrderCompensation compensation = compensationRepository.save(
                    OrderCompensation.open(orderId, CompensationReason.PAID_BUT_CANCELLED,
                            "취소된 주문에 payment.completed 도착 — 환불 필요"));
            // 감지 원장과 요청 Outbox 는 같은 트랜잭션이다(ADR-0018 D1 원자성 불변식) — 부분 커밋은
            // "감지했는데 아무도 환불하지 않는" 영구 미결을 만든다. detectedAt 은 원장에 기록된
            // 값 그대로 실어, 두 기록이 같은 사실임을 코드로 고정한다.
            outboxEventPublisher.publishCompensationRequested(orderId,
                    com.peekcart.global.outbox.dto.CompensationReason.PAID_BUT_CANCELLED,
                    compensation.getDetectedAt());
            sagaMetrics.compensationDetected();
        }
        log.error("PAID_BUT_CANCELLED — 취소된 주문에 결제 완료 도착, 환불 요청 발행(원장 기록). orderId={}", orderId);
        slackPort.send("[보상 요청] PAID_BUT_CANCELLED orderId=" + orderId
                + " — 취소된 주문의 결제가 승인됨. 환불 요청 발행.");
    }

    /** 문자열 필드를 읽는다. 부재/null 은 {@code null} — 구 메시지 내성(ADR-0012 D2). */
    private String readText(JsonNode payload, String field) {
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }

    /**
     * 확정 시각을 읽는다. 부재/null 이면 소비 시각으로 대체한다 — 종결 시각이 비면 "닫혔는데
     * 언제인지 모르는" 원장이 되어 운영 조회가 불가능해진다.
     */
    private LocalDateTime readResolvedAt(JsonNode payload) {
        String raw = readText(payload, "resolvedAt");
        return raw == null ? LocalDateTime.now() : LocalDateTime.parse(raw);
    }

    /**
     * lease 만료 시각을 읽는다. 필드 부재/null 은 lease 미부여(구 메시지)로 간주해 {@code null} 을 돌려주며,
     * 이 경우 주문은 만료 판정 대상에서 제외된다 — 파싱 실패로 소비를 깨뜨리지 않는다(하위 호환, ADR-0012 D2).
     */
    private LocalDateTime readReservationExpiresAt(JsonNode payload) {
        JsonNode node = payload.get("reservationExpiresAt");
        if (node == null || node.isNull()) {
            return null;
        }
        return LocalDateTime.parse(node.asText());
    }
}
