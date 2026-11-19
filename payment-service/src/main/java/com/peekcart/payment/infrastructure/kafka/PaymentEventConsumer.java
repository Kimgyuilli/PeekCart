package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.payment.application.PaymentRefundService;
import com.peekcart.payment.application.PaymentApprovalService;
import com.peekcart.global.port.SlackPort;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentCancellation;
import com.peekcart.payment.domain.model.PaymentReservationResult;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.repository.PaymentCancellationRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.domain.repository.PaymentReservationResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

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

    private static final String GROUP_STOCK_APPROVAL = "payment-svc-stock-approval-group";
    private static final String GROUP_ORDER_CREATED = "payment-svc-order-created-group";
    private static final String GROUP_STOCK_RESULT = "payment-svc-stock-result-group";
    private static final String GROUP_ORDER_CANCELLED = "payment-svc-order-cancelled-group";

    private final PaymentApprovalService approvalService;
    private final PaymentRepository paymentRepository;
    private final PaymentCancellationRepository paymentCancellationRepository;
    private final PaymentReservationResultRepository paymentReservationResultRepository;
    private final IdempotencyChecker idempotencyChecker;
    private final KafkaMessageParser kafkaMessageParser;
    private final PaymentRefundService paymentRefundService;
    private final SlackPort slackPort;

    @KafkaListener(topics = "stock.approval.result", groupId = GROUP_STOCK_APPROVAL)
    @Transactional
    public void handleStockApprovalResult(String message) {
        JsonNode root = kafkaMessageParser.parse(message);
        JsonNode payload = root.get("payload");
        if (!payload.has("granted") || !payload.get("granted").isBoolean()) {
            throw new IllegalArgumentException("stock.approval.result granted는 필수 boolean이다");
        }
        idempotencyChecker.executeIfNew(root.get("eventId").asText(), GROUP_STOCK_APPROVAL,
                () -> approvalService.applyStockApprovalResult(payload.get("orderId").asLong(),
                        payload.get("granted").asBoolean()));
    }

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
            paymentRepository.save(payment); // IDENTITY 라 여기서 INSERT 된다 — 아래 잠금 조회보다 먼저여야 한다
            // order.cancelled 가 선도착해 취소 marker 가 있으면 즉시 종료(선도착 누수 방지).
            // INSERT 뒤 잠금 조회라야 취소 consumer 의 커밋을 기다려 본다(D-121). 삭제는 PK 로 한다.
            paymentCancellationRepository.findByOrderIdForUpdate(orderId).ifPresent(cancellation -> {
                payment.cancelBeforePayment();
                paymentCancellationRepository.delete(cancellation);
                log.debug("선도착 취소 marker 적용 — Payment 를 CANCELLED 로 전환, orderId={}", orderId);
            });
            // 예약 결과 선도착 marker(D-112). INSERT 뒤 잠금 조회라야 예약 결과 consumer 의 커밋을 기다려 본다.
            // 취소가 우선이다: 위에서 CANCELLED 가 됐으면 적용하지 않고 지우기만 한다.
            paymentReservationResultRepository.findByOrderIdForUpdate(orderId).ifPresent(result -> {
                if (payment.getStatus() == PaymentStatus.PENDING) {
                    payment.markReadyForPayment(result.getReservationExpiresAt());
                }
                paymentReservationResultRepository.delete(result);
                log.debug("선도착 예약 결과 marker 적용 — orderId={}, status={}", orderId, payment.getStatus());
            });
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
            LocalDateTime expiresAt = readReservationExpiresAt(payload);
            // payment 의 order.created 소비가 늦거나 DLQ 로 갔으면 Payment 가 없다. 재시도는 영구 실패에서도
            // 파티션을 막으므로 marker 로 영속하고 handleOrderCreated 가 적용한다(D-112).
            // 잠금 조회여야 한다: 행이 없으면 키 구간을 잠가 Payment INSERT 를 이 커밋 뒤로 세운다.
            // 비잠금이면 미커밋 Payment 를 못 보고 marker 를 남겨, 이미 marker 조회를 끝낸 생성 쪽이 놓친다.
            paymentRepository.findByOrderIdForUpdate(orderId).ifPresentOrElse(payment -> {
                payment.markReadyForPayment(expiresAt);
                log.debug("결제 준비 완료(reserved=true) — orderId={}, lease 만료={}", orderId, expiresAt);
            }, () -> {
                if (!paymentReservationResultRepository.existsByOrderId(orderId)) {
                    paymentReservationResultRepository.save(PaymentReservationResult.of(orderId, expiresAt));
                }
                log.debug("예약 결과 선도착 — marker 영속, orderId={}", orderId);
            });
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
            // 비잠금 조회가 비었을 때만 잠금 조회로 다시 본다(D-121). 행이 없으면 키 구간을 잠가 Payment INSERT 를
            // 이 커밋 뒤로 세우고, 미커밋 INSERT 가 있으면 그 커밋을 기다려 본다. 있는 경로를 잠그지 않는 이유:
            // APPROVED 면 결제 행 잠금 뒤 환불 INSERT 가 되어, 환불 행 잠금 뒤 결제를 고치는 finalizeOutcome 과 교착한다.
            Optional<Payment> found = paymentRepository.findByOrderId(orderId)
                    .or(() -> paymentRepository.findByOrderIdForUpdate(orderId));
            found.ifPresentOrElse(payment -> {
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
