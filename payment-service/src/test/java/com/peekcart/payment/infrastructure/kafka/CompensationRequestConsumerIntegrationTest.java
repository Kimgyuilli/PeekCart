package com.peekcart.payment.infrastructure.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.payment.application.RefundOutcome;
import com.peekcart.payment.application.PaymentRefundService;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.model.RefundStatus;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 요청 토픽 2종 소비가 <b>하나의 fence 로 수렴</b>하는지 검증한다 (계획 P14 — ④-c-1b, ADR-0018 D1/D3).
 *
 * <p>ADR-0018 D1 은 cross-topic 순서를 보장하지 않는다고 못박았다 — 따라서 요청 2종과 Payment
 * 로컬 감지의 <b>모든 선후·중복 조합</b>에서 원장이 1행이고 최종 결과가 같아야 한다. 그 수렴
 * 책임은 순서 제어가 아니라 {@code payment_refunds.order_id} UNIQUE 에 있다.
 */
@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 배경 스케줄러는 테스트 기본 off 다(ADR-0029). 원장을 claim 해 상태 판정을 흐리지 않는다.
        // ADR-0029: 리스너도 기본 off 다. 실제 Kafka 왕복으로 두 토픽 소비 배선을 확인하는 케이스가 있어 켠다.
        "app.kafka.listener.enabled=true"
})
// 켠 자율 writer 의 수명을 자기 클래스에 가둔다 (ADR-0029 D3) — context 가 캐시된 채 남으면
// 리스너가 이후 클래스의 공유 브로커·DB 를 계속 고친다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("환불 요청 토픽 소비 통합 테스트")
class CompensationRequestConsumerIntegrationTest extends AbstractIntegrationTest {

    @Autowired CompensationRequestConsumer consumer;
    @Autowired PaymentEventConsumer paymentEventConsumer;
    @Autowired PaymentRefundService refundService;
    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("[SAGA-REFUND-CRASH-D] stock.compensation.requested 소비 → 원장 REQUESTED 1행 (PG 호출 없음)")
    void stockRequest_createsRequestedLedger() {
        Payment payment = seedApprovedPayment(2001L);

        consumer.handleStockCompensationRequested(
                request("stock.compensation.requested", 2001L, "PAID_BUT_UNRESERVED"));

        assertThat(refundService.find(2001L)).isPresent();
        assertThat(refundService.find(2001L).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(countRefunds(2001L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("order.compensation.requested 소비 → 원장 REQUESTED 1행")
    void orderRequest_createsRequestedLedger() {
        seedApprovedPayment(2002L);

        consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2002L, "PAID_BUT_CANCELLED"));

        assertThat(refundService.find(2002L).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(countRefunds(2002L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("요청 2종이 순서 무관하게 도착해도 원장은 1행 — stock 먼저")
    void bothRequests_stockFirst_singleLedgerRow() {
        seedApprovedPayment(2003L);

        consumer.handleStockCompensationRequested(
                request("stock.compensation.requested", 2003L, "PAID_BUT_UNRESERVED"));
        consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2003L, "PAID_BUT_CANCELLED"));

        assertThat(countRefunds(2003L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("요청 2종이 순서 무관하게 도착해도 원장은 1행 — order 먼저")
    void bothRequests_orderFirst_singleLedgerRow() {
        seedApprovedPayment(2004L);

        consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2004L, "PAID_BUT_CANCELLED"));
        consumer.handleStockCompensationRequested(
                request("stock.compensation.requested", 2004L, "PAID_BUT_UNRESERVED"));

        assertThat(countRefunds(2004L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("[SAGA-REFUND-FENCE-CONVERGE] 로컬 감지가 먼저여도 뒤늦은 요청과 1행으로 접힌다 (세 진입점 수렴)")
    void localDetectionThenRequest_singleLedgerRow() {
        Payment payment = seedApprovedPayment(2005L);

        // Payment 로컬 감지 경로 (order.cancelled 의 APPROVED 분기)
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2005L, "PAID_BUT_CANCELLED"));
        consumer.handleStockCompensationRequested(
                request("stock.compensation.requested", 2005L, "PAID_BUT_UNRESERVED"));

        assertThat(countRefunds(2005L)).isEqualTo(1L);
        assertThat(refundService.find(2005L).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    @Test
    @DisplayName("중복 요청(새 eventId, DLQ 재발행 모사)도 예외 없이 no-op — DLQ 로 보내지 않는다")
    void duplicateRequest_isNoopNotException() {
        seedApprovedPayment(2006L);

        consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2006L, "PAID_BUT_CANCELLED"));

        assertThatCode(() -> consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2006L, "PAID_BUT_CANCELLED")))
                .doesNotThrowAnyException();
        assertThat(countRefunds(2006L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("과금이 성립하지 않은 결제(CANCELLED)에는 원장을 만들지 않는다")
    void nonApprovedPayment_noLedger() {
        seedCancelledPayment(2007L);

        consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2007L, "PAID_BUT_CANCELLED"));

        assertThat(refundService.find(2007L)).isEmpty();
    }

    @Test
    @DisplayName("결제 미존재는 transient — 예외로 재시도시킨다(원장 미생성)")
    void missingPayment_throwsForRetry() {
        assertThatThrownBy(() -> consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2008L, "PAID_BUT_CANCELLED")))
                .isInstanceOf(PaymentException.class);

        assertThat(refundService.find(2008L)).isEmpty();
    }

    @Test
    @DisplayName("모르는 reason 값은 전방 호환 — UNKNOWN 으로 정규화하고 환불은 시작된다")
    void unknownReason_stillCreatesLedger() {
        seedApprovedPayment(2009L);

        assertThatCode(() -> consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2009L, "SOME_FUTURE_REASON")))
                .doesNotThrowAnyException();

        assertThat(refundService.find(2009L).orElseThrow().getStatus()).isEqualTo(RefundStatus.REQUESTED);
    }

    /**
     * 미지 값(전방 호환)과 <b>필수 필드 부재</b>는 다르게 다뤄야 한다. ADR-0012 D2 의 하위호환 규칙은
     * 필드 추가만 허용하고 필수 필드 삭제는 허용하지 않으므로, 부재는 잘못 생성된 메시지다 —
     * 그걸로 금전 동작을 개시하면 fail-open 이다.
     */
    @Test
    @DisplayName("필수 필드(reason·detectedAt) 부재는 거부하고 원장을 만들지 않는다")
    void missingRequiredField_rejectedWithoutLedger() {
        seedApprovedPayment(2010L);
        Map<String, Object> noReason = new HashMap<>();
        noReason.put("orderId", 2010L);
        noReason.put("detectedAt", LocalDateTime.now().toString());

        assertThatThrownBy(() -> consumer.handleOrderCompensationRequested(
                envelope("order.compensation.requested", noReason)))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, Object> noDetectedAt = new HashMap<>();
        noDetectedAt.put("orderId", 2010L);
        noDetectedAt.put("reason", "PAID_BUT_CANCELLED");

        assertThatThrownBy(() -> consumer.handleOrderCompensationRequested(
                envelope("order.compensation.requested", noDetectedAt)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(refundService.find(2010L)).isEmpty();
    }

    @Test
    @DisplayName("orderId 가 null·비숫자·0 이면 거부한다 — asLong() 이 0 으로 축약하는 경로 차단")
    void invalidOrderId_rejected() {
        Map<String, Object> nullId = new HashMap<>();
        nullId.put("orderId", null);
        nullId.put("reason", "PAID_BUT_CANCELLED");
        nullId.put("detectedAt", LocalDateTime.now().toString());

        Map<String, Object> textId = new HashMap<>();
        textId.put("orderId", "not-a-number");
        textId.put("reason", "PAID_BUT_CANCELLED");
        textId.put("detectedAt", LocalDateTime.now().toString());

        Map<String, Object> zeroId = new HashMap<>();
        zeroId.put("orderId", 0L);
        zeroId.put("reason", "PAID_BUT_CANCELLED");
        zeroId.put("detectedAt", LocalDateTime.now().toString());

        for (Map<String, Object> payload : List.of(nullId, textId, zeroId)) {
            assertThatThrownBy(() -> consumer.handleOrderCompensationRequested(
                    envelope("order.compensation.requested", payload)))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(countAllRefunds()).isZero();
    }

    /**
     * <b>c1:1 (P0) 회귀</b> — cross-topic 순서가 보장되지 않으므로 {@code payment.refunded} 가
     * 소비자의 감지보다 먼저 도착할 수 있다. 그때 소비자는 원장이 없어 회신을 no-op 하고
     * {@code processed_events} 로 봉인하므로, 뒤늦게 만든 원장은 <b>회신을 다시 받아야만</b> 닫힌다.
     * 재발행이 없으면 그 원장은 영구 미결이고 R-2 가 되돌아간다.
     */
    @Test
    @DisplayName("이미 종결된 환불에 뒤늦은 요청이 오면 회신을 재발행한다 (늦은 감지 원장 종결용)")
    void lateRequestOnResolvedRefund_republishesReply() {
        Payment payment = seedApprovedPayment(2011L);
        refundService.requestRefund(payment, "PAID_BUT_CANCELLED");
        refundService.claimRequested(2011L);
        refundService.finalizeOutcome(2011L, generationOf(2011L),
                RefundOutcome.succeeded("{\"status\":\"CANCELED\"}"), 1);
        assertThat(findRefundedOutbox(2011L)).hasSize(1);

        // Order 가 뒤늦게 감지해 요청을 보낸다 — fence 는 이미 존재하고 종결됐다
        consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2011L, "PAID_BUT_CANCELLED"));

        assertThat(findRefundedOutbox(2011L)).hasSize(2);
        assertThat(countRefunds(2011L)).isEqualTo(1L);   // 원장은 여전히 1행
    }

    @Test
    @DisplayName("종결 전(REQUESTED) 중복 요청은 회신을 재발행하지 않는다 — 아직 결과가 없다")
    void duplicateRequestBeforeResolution_doesNotRepublish() {
        seedApprovedPayment(2012L);

        consumer.handleOrderCompensationRequested(
                request("order.compensation.requested", 2012L, "PAID_BUT_CANCELLED"));
        consumer.handleStockCompensationRequested(
                request("stock.compensation.requested", 2012L, "PAID_BUT_UNRESERVED"));

        assertThat(findRefundedOutbox(2012L)).isEmpty();
    }


    // ---------------- P14: listener 배선 (실제 Kafka 왕복) ----------------

    /**
     * 위 테스트들은 listener 메서드를 직접 호출하므로 <b>토픽명·consumer group·기본
     * {@code kafkaListenerContainerFactory}·역직렬화 배선이 전부 틀려도 통과</b>한다.
     * 조합 전수는 직접 호출로 빠르게 돌리고, <b>배선은 여기서 실제 broker 왕복으로 고정</b>한다.
     */
    @Test
    @DisplayName("실제 Kafka 발행 → 두 요청 listener 가 배선되어 원장을 만든다 (group·factory 계약)")
    void requestListeners_areWiredToBroker() {
        seedApprovedPayment(2101L);
        seedApprovedPayment(2102L);

        kafkaTemplate.send("stock.compensation.requested", "2101",
                request("stock.compensation.requested", 2101L, "PAID_BUT_UNRESERVED"));
        kafkaTemplate.send("order.compensation.requested", "2102",
                request("order.compensation.requested", 2102L, "PAID_BUT_CANCELLED"));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(refundService.find(2101L)).isPresent();
            assertThat(refundService.find(2102L)).isPresent();
        });
    }

    // ---------------- helpers ----------------

    private String request(String eventType, Long orderId, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("reason", reason);
        payload.put("detectedAt", LocalDateTime.now().toString());
        return envelope(eventType, payload);
    }

    private String envelope(String eventType, Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(new KafkaEventEnvelope(
                    UUID.randomUUID().toString(), eventType, LocalDateTime.now(), payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private long countAllRefunds() {
        EntityManager em = emf.createEntityManager();
        try {
            return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM payment_refunds")
                    .getSingleResult()).longValue();
        } finally {
            em.close();
        }
    }

    private long generationOf(Long orderId) {
        return refundService.find(orderId).orElseThrow().getGeneration();
    }

    private List<OutboxEvent> findRefundedOutbox(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT e FROM OutboxEvent e WHERE e.aggregateId = :id "
                            + "AND e.eventType = 'payment.refunded'", OutboxEvent.class)
                    .setParameter("id", orderId.toString())
                    .getResultList();
        } finally {
            em.close();
        }
    }

    private long countRefunds(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM payment_refunds WHERE order_id = ?1")
                    .setParameter(1, orderId).getSingleResult()).longValue();
        } finally {
            em.close();
        }
    }

    private Payment seedApprovedPayment(Long orderId) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Payment payment = Payment.create(orderId, 42L, 50_000L);
        payment.assignPaymentKey("toss-key-" + orderId);
        payment.approve("카드", LocalDateTime.now());
        em.persist(payment);
        em.getTransaction().commit();
        em.close();
        return payment;
    }

    private void seedCancelledPayment(Long orderId) {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Payment payment = Payment.create(orderId, 42L, 50_000L);
        payment.cancelBeforePayment();
        em.persist(payment);
        em.flush();
        em.getTransaction().commit();
        em.close();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.CANCELLED);
    }
}
