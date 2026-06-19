package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.peekcart.global.idempotency.IdempotencyChecker;
import com.peekcart.global.kafka.KafkaMessageParser;
import com.peekcart.global.port.SlackPort;
import com.peekcart.payment.domain.exception.PaymentException;
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

    private void stubOrderCancelled() {
        ObjectNode root = om.createObjectNode();
        root.put("eventId", "evt-oc-1");
        ObjectNode payload = root.putObject("payload");
        payload.put("orderId", PaymentFixture.DEFAULT_ORDER_ID);
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
        then(slackPort).should(never()).send(anyString());
    }

    @Test
    @DisplayName("order.cancelled + APPROVED(과금-후-취소) → APPROVED 유지 + 운영 알림 발행")
    void orderCancelled_approved_keepsApprovedAndAlerts() {
        Payment payment = PaymentFixture.approvedPayment();
        given(paymentRepository.findByOrderId(PaymentFixture.DEFAULT_ORDER_ID)).willReturn(Optional.of(payment));
        stubOrderCancelled();

        consumer.handleOrderCancelled("msg");

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.APPROVED);
        then(slackPort).should().send(anyString());
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
