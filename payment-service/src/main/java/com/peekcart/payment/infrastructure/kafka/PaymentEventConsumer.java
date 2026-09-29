package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.payment.application.PaymentRefundService;
import com.peekcart.global.port.SlackPort;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentCancellation;
import com.peekcart.payment.domain.repository.PaymentCancellationRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 주문/재고 관련 Kafka 이벤트를 소비해 결제 로컬 상태를 갱신하는 Consumer.
 * <p>
 * 소비 토픽: {@code order.created}(Payment 생성), {@code stock.reservation.result}(reserve→pay 게이트),
 * {@code order.cancelled}(결제 시작 전 취소 게이트). OrderPort 동기 호출 제거에 따른 payment-로컬 모델.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentEventConsumer {

    private static final String GROUP_ORDER_CREATED = "payment-svc-order-created-group";
    private static final String GROUP_STOCK_RESULT = "payment-svc-stock-result-group";
    private static final String GROUP_ORDER_CANCELLED = "payment-svc-order-cancelled-group";

    private final PaymentRepository paymentRepository;
    private final PaymentCancellationRepository paymentCancellationRepository;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;
    private final PaymentRefundService paymentRefundService;
    private final SlackPort slackPort;

    /** 주문 생성 시 {@code PENDING} 상태의 Payment를 생성한다 (소유자 검증용 userId 포함). */
    @KafkaListener(topics = "order.created", groupId = GROUP_ORDER_CREATED)
    @Transactional
    public void handleOrderCreated(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_ORDER_CREATED, () -> {
            Long orderId = payload.get("orderId").asLong();
            Long userId = payload.get("userId").asLong();
            long totalAmount = payload.get("totalAmount").asLong();
            Payment payment = Payment.create(orderId, userId, totalAmount);
            // order.cancelled 가 선도착해 취소 marker 가 있으면 즉시 종료(선도착 누수 방지).
            if (paymentCancellationRepository.existsByOrderId(orderId)) {
                payment.cancelBeforePayment();
                paymentCancellationRepository.deleteByOrderId(orderId);
                log.debug("선도착 취소 marker 적용 — Payment 를 CANCELLED 로 생성, orderId={}", orderId);
            }
            paymentRepository.save(payment);
            log.debug("Payment 생성 — orderId={}, status={}", orderId, payment.getStatus());
        });
    }

    /**
     * 재고 예약 결과를 소비해 reserve→pay 게이트(ADR-0012 §D3)를 payment-로컬로 복원한다.
     * {@code reserved=true} 면 결제 진행 가능 상태로 표시한다.
     */
    @KafkaListener(topics = "stock.reservation.result", groupId = GROUP_STOCK_RESULT)
    @Transactional
    public void handleStockReservationResult(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_STOCK_RESULT, () -> {
            if (!payload.get("reserved").asBoolean()) {
                return;
            }
            Long orderId = payload.get("orderId").asLong();
            // order.created → stock.reservation.result 순서는 saga 가 보장하나, payment 의 order.created
            // 소비가 지연되면 미존재 가능 → 예외로 재시도(backoff)해 수렴시킨다.
            Payment payment = paymentRepository.findByOrderId(orderId)
                    .orElseThrow(() -> new PaymentException(ErrorCode.PAY_003));
            LocalDateTime expiresAt = readReservationExpiresAt(payload);
            payment.markReadyForPayment(expiresAt);
            log.debug("결제 준비 완료(reserved=true) — orderId={}, lease 만료={}", orderId, expiresAt);
        });
    }

    /**
     * 결제 시작 전 주문 취소를 소비해 취소 게이트를 payment-로컬로 복원한다.
     * APPROVED 인데 취소가 도착하면(과금-후-취소) 덮어쓰지 않고 보상 경로로 알린다 (ADR-0012 D3 ④).
     */
    @KafkaListener(topics = "order.cancelled", groupId = GROUP_ORDER_CANCELLED)
    @Transactional
    public void handleOrderCancelled(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        String eventId = root.get("eventId").asText();
        JsonNode payload = root.get("payload");

        idempotencyChecker.executeIfNew(eventId, GROUP_ORDER_CANCELLED, () -> {
            Long orderId = payload.get("orderId").asLong();
            paymentRepository.findByOrderId(orderId).ifPresentOrElse(payment -> {
                if (payment.cancelBeforePayment()) {
                    // APPROVED 후 취소(과금-후-취소) → 환불 원장에 REQUESTED 를 영속한다 (ADR-0018 D4).
                    // Slack 은 배포 구성상 no-op 이라 종결은커녕 기록도 되지 못했다 — 원장이 진실이다.
                    // PG 호출은 여기서 하지 않는다: 소비 트랜잭션이 롤백되면 fence 행이 사라져
                    // fence 자체가 무효가 된다(ADR-0018 D3). 실행은 dispatcher 소관.
                    paymentRefundService.requestRefund(payment, "PAID_BUT_CANCELLED");
                    log.warn("과금-후-취소 감지 — 환불 요청 기록(ADR-0018 D4), orderId={}", orderId);
                }
            }, () -> {
                // order.cancelled 가 order.created 보다 선도착(Payment 미존재): orderId 기준 취소 marker 를 영속화.
                // Payment 생성 시점(handleOrderCreated)에 즉시 CANCELLED 로 적용돼, DLQ 로 빠져도 누수가 없다.
                if (!paymentCancellationRepository.existsByOrderId(orderId)) {
                    paymentCancellationRepository.save(PaymentCancellation.of(orderId));
                }
                log.debug("order.cancelled 선도착 — 취소 marker 영속, orderId={}", orderId);
            });
        });
    }

    /**
     * lease 만료 시각을 읽는다. 필드 부재/null 은 lease 미부여(구 메시지)로 간주해 {@code null} 을 돌려주며,
     * 이 경우 만료 판정 없이 기존 동작을 유지한다(하위 호환, ADR-0012 D2).
     */
    private LocalDateTime readReservationExpiresAt(JsonNode payload) {
        JsonNode node = payload.get("reservationExpiresAt");
        if (node == null || node.isNull()) {
            return null;
        }
        return LocalDateTime.parse(node.asText());
    }
}
