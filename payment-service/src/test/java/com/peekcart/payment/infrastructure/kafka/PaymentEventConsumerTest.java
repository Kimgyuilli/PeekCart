package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.global.port.SlackPort;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.application.PaymentRefundService;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentCancellation;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.repository.PaymentCancellationRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.support.ServiceTest;
import com.peekcart.support.fixture.PaymentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ServiceTest
@DisplayName("PaymentEventConsumer 단위 테스트 (payment-로컬 게이트)")
class PaymentEventConsumerTest {

    @InjectMocks PaymentEventConsumer consumer;
    @Mock PaymentRepository paymentRepository;
    @Mock PaymentCancellationRepository paymentCancellationRepository;
    @Mock IdempotencyChecker idempotencyChecker;
    @Mock KafkaMessageParser kafkaMessageParser;
    @Mock SlackPort slackPort;
    @Mock PaymentRefundService paymentRefundService;

    private final ObjectMapper om = new ObjectMapper();

    private void runIdempotent() {
        given(idempotencyChecker.executeIfNew(any(), any(), any())).willAnswer(inv -> {
            ((Runnable) inv.getArgument(2)).run();
            return true;
        });
    }

    private void stubStockResult(boolean reserved) {
        ObjectNode root = om.createObjectNode();
        root.put("eventId", "evt-sr-1");
        ObjectNode payload = root.putObject("payload");
        payload.put("orderId", PaymentFixture.DEFAULT_ORDER_ID);
        payload.put("reserved", reserved);
        given(kafkaMessageParser.parse("msg")).willReturn((JsonNode) root);
        runIdempotent();
    }

    /** 구 메시지 형태(items·reason 부재) — 하위호환 대조군. */
    private void stubOrderCancelled() {
        ObjectNode root = om.createObjectNode();
        root.put("eventId", "evt-oc-1");
        ObjectNode payload = root.putObject("payload");
        payload.put("orderId", PaymentFixture.DEFAULT_ORDER_ID);
        given(kafkaMessageParser.parse("msg")).willReturn((JsonNode) root);
        runIdempotent();
    }

    /** 신 메시지 형태(items·reason 포함) — Payment 는 orderId 만 읽으므로 동작이 같아야 한다. */
    private void stubOrderCancelledWithItemsAndReason() {
        ObjectNode root = om.createObjectNode();
        root.put("eventId", "evt-oc-2");
        ObjectNode payload = root.putObject("payload");
        payload.put("orderId", PaymentFixture.DEFAULT_ORDER_ID);
        payload.put("orderNumber", "ORD-001");
        payload.put("reason", "PAYMENT_FAILED");
        ObjectNode item = payload.putArray("items").addObject();
        item.put("productId", 11L);
        item.put("quantity", 2);
        given(kafkaMessageParser.parse("msg")).willReturn((JsonNode) root);
        runIdempotent();
    }

    @Test
    @DisplayName("stock.reservation.result reserved=true → 결제 준비(readyForPayment) 표시")
    void stockResult_reserved_marksReady() {
        Payment payment = PaymentFixture.pendingPaymentWithId();
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(payment));
        stubStockResult(true);

        consumer.handleStockReservationResult("msg");

        assertThat(payment.isReadyForPayment()).isTrue();
    }

    @Test
    @DisplayName("stock.reservation.result reserved=false → no-op (조회조차 하지 않음)")
    void stockResult_notReserved_noop() {
        stubStockResult(false);

        consumer.handleStockReservationResult("msg");

        then(paymentRepository).should(never()).findByOrderId(any());
    }

    @Test
    @DisplayName("stock.reservation.result: Payment 미존재(order.created 미처리) → PAY-003 으로 재시도")
    void stockResult_paymentMissing_throwsForRetry() {
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(Optional.empty());
        stubStockResult(true);

        assertThatThrownBy(() -> consumer.handleStockReservationResult("msg"))
                .isInstanceOf(PaymentException.class);
    }

    @Test
    @DisplayName("order.cancelled + PENDING → CANCELLED 종료, 알림 없음")
    void orderCancelled_pending_cancels() {
        Payment payment = PaymentFixture.pendingPaymentWithId();
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(payment));
        stubOrderCancelled();

        consumer.handleOrderCancelled("msg");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        // 결제 시작 전 취소 — 과금이 없으므로 환불 요청을 만들지 않는다
        then(paymentRefundService).should(never()).requestRefund(any(), anyString());
    }

    @Test
    @DisplayName("order.cancelled + APPROVED(과금-후-취소) → APPROVED 유지 + 환불 요청 기록(ADR-0018 D4)")
    void orderCancelled_approved_keepsApprovedAndRequestsRefund() {
        Payment payment = PaymentFixture.approvedPayment();
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(payment));
        stubOrderCancelled();

        consumer.handleOrderCancelled("msg");

        // 과금은 살아있으므로 상태를 덮지 않고, 환불 요청을 원장에 남긴다.
        // Slack 은 배포 구성상 no-op 이라 종결 근거가 못 된다(ADR-0018 C1/D4).
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        then(paymentRefundService).should().requestRefund(payment, "PAID_BUT_CANCELLED");
    }

    @Test
    @DisplayName("order.cancelled 에 items·reason 이 추가돼도 동작이 같다 (계약 확장 내성, P5·P6)")
    void orderCancelled_withItemsAndReason_behavesSame() {
        Payment payment = PaymentFixture.pendingPaymentWithId();
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(payment));
        stubOrderCancelledWithItemsAndReason();

        consumer.handleOrderCancelled("msg");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        // 결제 시작 전 취소 — 과금이 없으므로 환불 요청을 만들지 않는다
        then(paymentRefundService).should(never()).requestRefund(any(), anyString());
    }

    @Test
    @DisplayName("order.cancelled 선도착(Payment 미존재) → 취소 marker 영속(누수 방지, throw 안 함)")
    void orderCancelled_paymentMissing_recordsMarker() {
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(Optional.empty());
        given(paymentCancellationRepository.existsByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(false);
        stubOrderCancelled();

        consumer.handleOrderCancelled("msg");

        then(paymentCancellationRepository).should().save(any(PaymentCancellation.class));
    }

    private void stubOrderCreated() {
        ObjectNode root = om.createObjectNode();
        root.put("eventId", "evt-oc-created");
        ObjectNode payload = root.putObject("payload");
        payload.put("orderId", PaymentFixture.DEFAULT_ORDER_ID);
        payload.put("userId", PaymentFixture.DEFAULT_USER_ID);
        payload.put("totalAmount", PaymentFixture.DEFAULT_AMOUNT);
        given(kafkaMessageParser.parse("msg")).willReturn((JsonNode) root);
        runIdempotent();
    }

    @Test
    @DisplayName("order.created → userId 포함 Payment(PENDING) 생성")
    void orderCreated_createsPaymentWithUserId() {
        stubOrderCreated();
        given(paymentCancellationRepository.existsByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(false);

        consumer.handleOrderCreated("msg");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        then(paymentRepository).should().save(captor.capture());
        Payment saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(PaymentFixture.DEFAULT_USER_ID);
        assertThat(saved.getOrderId()).isEqualTo(PaymentFixture.DEFAULT_ORDER_ID);
        assertThat(saved.getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    @DisplayName("order.created: 선도착 취소 marker 가 있으면 Payment 를 CANCELLED 로 생성(누수 0)")
    void orderCreated_withPriorCancellationMarker_createsCancelled() {
        stubOrderCreated();
        given(paymentCancellationRepository.existsByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(true);

        consumer.handleOrderCreated("msg");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        then(paymentRepository).should().save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.CANCELLED);
        then(paymentCancellationRepository).should().deleteByOrderId(PaymentFixture.DEFAULT_ORDER_ID);
    }
}
