package com.peekcart.product.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.product.domain.model.StockReservation;
import com.peekcart.product.domain.repository.StockReservationRepository;
import com.peekcart.product.infrastructure.kafka.RefundResultConsumer;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import java.util.stream.Collectors;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * Product 측 환불 보상 계약 통합 테스트 (계획 P14 — ④-c-1b).
 *
 * <p>보는 것: 감지 marker 와 요청 Outbox 의 <b>원자성</b>(ADR-0018 D1), 회신에 의한 <b>종결 기록</b>
 * (marker ≠ 종결, D4), backfill 의 <b>멱등</b>. 세 가지 모두 실제 MySQL 과 트랜잭션 경계가 있어야
 * 판정되므로 단위 mock 으로 대체하지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // ADR-0029: 리스너는 테스트에서 기본 off 다. 실제 Kafka 왕복으로 payment.refunded listener 배선을 고정하는 케이스가 있다.
        "app.kafka.listener.enabled=true"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
// 켠 자율 writer 의 수명을 자기 클래스에 가둔다 (ADR-0029 D3) — context 가 캐시된 채 남으면
// 리스너가 이후 클래스의 공유 브로커·DB 를 계속 고친다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("Product 환불 보상 계약 통합 테스트")
class StockCompensationRefundIntegrationTest extends AbstractIntegrationTest {

    private static final String REQUEST_EVENT_TYPE = "stock.compensation.requested";
    private static final long ORDER_ID = 7_001L;

    @Autowired StockReservationService reservationService;
    @Autowired StockReservationRepository reservationRepository;
    @Autowired RefundResultConsumer refundResultConsumer;
    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    @MockitoSpyBean OutboxEventRepository outboxEventRepository;

    // 배경 스케줄러는 테스트 기본 off(ADR-0029) — outbox poller 가 위 spy 와 경합하거나 PENDING 을
    // PUBLISHED 로 바꾸지 않는다. 발행 경로가 필요한 검사는 kafkaTemplate.send 로 직접 넣는다.

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // ---------------- P11: 트리거 발행 + 원자성 ----------------

    @Test
    @DisplayName("PAID_BUT_UNRESERVED 감지 → marker 와 stock.compensation.requested 가 함께 커밋된다")
    void detection_publishesRequestWithContractKeys() throws Exception {
        seedReleasedReservation();

        reservationService.confirm(ORDER_ID);

        StockReservation reservation = reservationRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(reservation.getCompensatedAt()).isNotNull();

        List<OutboxEvent> requests = findRequestOutbox();
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).getAggregateType()).isEqualTo("PRODUCT");
        assertThat(requests.get(0).getAggregateId()).isEqualTo(String.valueOf(ORDER_ID));

        JsonNode payload = objectMapper.readTree(requests.get(0).getPayload()).get("payload");
        assertThat(payload.get("orderId").asLong()).isEqualTo(ORDER_ID);
        assertThat(payload.get("reason").asText()).isEqualTo("PAID_BUT_UNRESERVED");
        // detectedAt 은 marker(compensated_at) 와 정확히 같아야 한다 — 근거는 order 측과 동일하다.
        // 감지 시각이 원천에서 저장소 정밀도로 확정되므로 DB 왕복 후에도 등식이 성립한다.
        assertThat(LocalDateTime.parse(payload.get("detectedAt").asText()))
                .isEqualTo(reservation.getCompensatedAt());
    }

    @Test
    @DisplayName("요청 Outbox 저장이 실패하면 감지 marker 도 함께 롤백된다 — 부분 커밋 없음")
    void outboxFailure_rollsBackCompensationMarker() {
        seedReleasedReservation();
        willThrow(new RuntimeException("outbox down")).given(outboxEventRepository).save(any());

        assertThatThrownBy(() -> reservationService.confirm(ORDER_ID))
                .isInstanceOf(RuntimeException.class);

        assertThat(reservationRepository.findByOrderId(ORDER_ID).orElseThrow().getCompensatedAt()).isNull();
    }

    @Test
    @DisplayName("재감지(marker 존재)에는 요청을 재발행하지 않는다 — 요청 1건")
    void redetection_doesNotRepublishRequest() {
        seedReleasedReservation();

        reservationService.confirm(ORDER_ID);
        reservationService.confirm(ORDER_ID);

        assertThat(findRequestOutbox()).hasSize(1);
    }

    // ---------------- P12: 회신 소비 종결 ----------------

    @Test
    @DisplayName("[SAGA-REFUND-RESULT-PRODUCT-SUCCEEDED] payment.refunded(SUCCEEDED) → 종결 컬럼 기록 (marker 와 별개)")
    void refundSucceeded_recordsClosure() {
        seedReleasedReservation();
        LocalDateTime resolvedAt = LocalDateTime.now().withNano(0);

        refundResultConsumer.handlePaymentRefunded(refunded("SUCCEEDED", null, resolvedAt));

        StockReservation reservation = reservationRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(reservation.getRefundResult()).isEqualTo("SUCCEEDED");
        assertThat(reservation.getRefundResolvedAt()).isEqualTo(resolvedAt);
        assertThat(reservation.getRefundFailureCode()).isNull();
        // 감지 marker 는 종결과 별개다 — 회신만으로 marker 가 생기지 않는다
        assertThat(reservation.getCompensatedAt()).isNull();
    }

    @Test
    @DisplayName("[SAGA-REFUND-RESULT-PRODUCT-FAILED] payment.refunded(FAILED) → 실패 코드와 함께 기록")
    void refundFailed_recordsFailureCode() {
        seedReleasedReservation();

        refundResultConsumer.handlePaymentRefunded(
                refunded("FAILED", "NOT_CANCELABLE_AMOUNT", LocalDateTime.now().withNano(0)));

        StockReservation reservation = reservationRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(reservation.getRefundResult()).isEqualTo("FAILED");
        assertThat(reservation.getRefundFailureCode()).isEqualTo("NOT_CANCELABLE_AMOUNT");
    }

    @Test
    @DisplayName("[SAGA-REFUND-RESULT-PRODUCT-UNRESOLVED] payment.refunded(UNRESOLVED) → 아무것도 기록하지 않는다")
    void refundUnresolved_recordsNothing() {
        seedReleasedReservation();

        refundResultConsumer.handlePaymentRefunded(refunded("UNRESOLVED", null, LocalDateTime.now().withNano(0)));

        assertThat(reservationRepository.findByOrderId(ORDER_ID).orElseThrow().getRefundResult()).isNull();
    }

    @Test
    @DisplayName("종결 후 도착한 회신은 종착 결과를 덮지 않는다")
    void redeliveredReply_doesNotOverwriteClosure() {
        seedReleasedReservation();
        LocalDateTime first = LocalDateTime.now().withNano(0);

        refundResultConsumer.handlePaymentRefunded(refunded("SUCCEEDED", null, first));
        refundResultConsumer.handlePaymentRefunded(refunded("FAILED", "LATE", first.plusHours(1)));

        StockReservation reservation = reservationRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(reservation.getRefundResult()).isEqualTo("SUCCEEDED");
        assertThat(reservation.getRefundFailureCode()).isNull();
    }

    @Test
    @DisplayName("예약 원장이 없는 회신(다른 감지 지점발)은 예외 없이 no-op")
    void replyWithoutReservation_isNoop() {
        assertThatCode(() -> refundResultConsumer.handlePaymentRefunded(
                refunded("SUCCEEDED", null, LocalDateTime.now().withNano(0))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("resolvedAt·failureCode 가 없는 구 메시지도 소비를 깨뜨리지 않는다")
    void legacyMessageWithoutOptionalFields_tolerated() {
        seedReleasedReservation();
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", ORDER_ID);
        payload.put("result", "SUCCEEDED");

        assertThatCode(() -> refundResultConsumer.handlePaymentRefunded(envelope(payload)))
                .doesNotThrowAnyException();

        StockReservation reservation = reservationRepository.findByOrderId(ORDER_ID).orElseThrow();
        assertThat(reservation.getRefundResult()).isEqualTo("SUCCEEDED");
        assertThat(reservation.getRefundResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("failureCode 없는 FAILED 는 UNKNOWN, SUCCEEDED 에 실린 failureCode 는 버린다 — 모순 상태 금지")
    void closureInvariants_enforcedByDomain() {
        seedReleasedReservation();
        Map<String, Object> failedNoCode = new HashMap<>();
        failedNoCode.put("orderId", ORDER_ID);
        failedNoCode.put("result", "FAILED");
        failedNoCode.put("resolvedAt", LocalDateTime.now().withNano(0).toString());

        refundResultConsumer.handlePaymentRefunded(envelope(failedNoCode));
        assertThat(reload().getRefundFailureCode()).isEqualTo("UNKNOWN");

        cleanDatabase();
        seedReleasedReservation();
        refundResultConsumer.handlePaymentRefunded(
                refunded("SUCCEEDED", "SHOULD_BE_DROPPED", LocalDateTime.now().withNano(0)));
        assertThat(reload().getRefundResult()).isEqualTo("SUCCEEDED");
        assertThat(reload().getRefundFailureCode()).isNull();
    }

    // ---------------- P14: listener 배선 (실제 Kafka 왕복) ----------------

    /**
     * 위 회신 테스트들은 listener 를 직접 호출하므로 토픽명·group·factory 배선이 틀려도 통과한다.
     * 배선 계약은 여기서 실제 broker 왕복으로 고정한다.
     */
    @Test
    @DisplayName("실제 Kafka 발행 → payment.refunded listener 가 배선되어 종결을 기록한다")
    void refundReplyListener_isWiredToBroker() {
        seedReleasedReservation();

        kafkaTemplate.send("payment.refunded", String.valueOf(ORDER_ID),
                refunded("SUCCEEDED", null, LocalDateTime.now().withNano(0)));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(reload().getRefundResult()).isEqualTo("SUCCEEDED"));
    }

    // ---------------- P11: backfill 멱등 ----------------

    @Test
    @DisplayName("backfill DML 2회 실행 → 2회차 0건")
    void backfill_isIdempotentAcrossReruns() {
        seedCompensatedReservationWithoutRequest();

        assertThat(runBackfillStatement(0)).isEqualTo(1);
        assertThat(runBackfillStatement(0)).isZero();
        runBackfillStatement(1);
        assertThat(findRequestOutbox()).hasSize(1);
    }

    /**
     * <b>c1:5/c2:1/c3:1 (P1) 회귀</b> — 조립 중 행이 {@code PENDING} 이면 롤링 배포 중 구 poller 가
     * 빈 payload 를 발행하고 PUBLISHED 로 봉인해 요청이 영구 유실된다.
     */
    @Test
    @DisplayName("backfill 1단계 행은 poller 에 노출되지 않는다 (PENDING 아님) — 2단계가 함께 전환")
    void backfillIntermediateRow_isNotPollable() {
        seedCompensatedReservationWithoutRequest();

        runBackfillStatement(0);
        assertThat(countByStatus("PENDING")).as("조립 중 행이 발행 대상이면 안 된다").isZero();
        assertThat(countByStatus("BACKFILL")).isEqualTo(1L);
        assertThat(outboxEventRepository.findPendingEvents(100)).isEmpty();

        runBackfillStatement(1);
        assertThat(countByStatus("PENDING")).isEqualTo(1L);
        assertThat(countByStatus("BACKFILL")).isZero();
    }

    @Test
    @DisplayName("backfill 2단계가 envelope 을 채우고 eventId 가 outbox_events.event_id 와 일치한다")
    void backfill_fillsEnvelopeWithMatchingEventId() throws Exception {
        seedCompensatedReservationWithoutRequest();
        runBackfillStatement(0);
        runBackfillStatement(1);

        OutboxEvent event = findRequestOutbox().get(0);
        JsonNode envelope = objectMapper.readTree(event.getPayload());
        assertThat(envelope.get("eventId").asText()).isEqualTo(event.getEventId());
        assertThat(envelope.get("eventType").asText()).isEqualTo(REQUEST_EVENT_TYPE);
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("payload").get("reason").asText()).isEqualTo("PAID_BUT_UNRESERVED");
        assertThat(envelope.get("payload").get("orderId").asLong()).isEqualTo(ORDER_ID);
    }

    private StockReservation reload() {
        return reservationRepository.findByOrderId(ORDER_ID).orElseThrow();
    }

    // ---------------- helpers ----------------

    /**
     * <b>운영 마이그레이션 V4 의 backfill DML 을 그대로 읽어 실행한다.</b>
     *
     * <p>SQL 을 테스트에 복제하면 실제 V4 가 틀리거나 사라져도 복제본만 맞으면 통과한다 — 검증
     * 대상이 마이그레이션이 아니라 복제본이 되는 false-green 이다. 파일이 단일 SSOT 다.
     *
     * @param index 0 = 1단계(INSERT), 1 = 2단계(UPDATE)
     */
    private int runBackfillStatement(int index) {
        return executeUpdate(backfillStatements().get(index));
    }

    /** V4 에서 DML(INSERT/UPDATE) 문장만 추출한다. DDL 은 Flyway 가 이미 적용했으므로 제외한다. */
    private List<String> backfillStatements() {
        String sql;
        try {
            sql = new String(getClass().getResourceAsStream(
                    "/db/migration/V4__stock_reservation_refund_closure.sql").readAllBytes(), UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("V4 마이그레이션을 읽을 수 없습니다", e);
        }
        List<String> statements = Arrays.stream(stripComments(sql).split(";"))
                .map(String::trim)
                .filter(st -> st.startsWith("INSERT") || st.startsWith("UPDATE"))
                .toList();
        assertThat(statements).as("V4 의 backfill DML 은 2문장이어야 한다").hasSize(2);
        return statements;
    }

    private String stripComments(String sql) {
        return Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
    }

    private long countByStatus(String status) {
        EntityManager em = emf.createEntityManager();
        try {
            return ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM outbox_events WHERE status = ?1 AND event_type = ?2")
                    .setParameter(1, status)
                    .setParameter(2, REQUEST_EVENT_TYPE)
                    .getSingleResult()).longValue();
        } finally {
            em.close();
        }
    }

    private int executeUpdate(String sql) {
        EntityManager em = emf.createEntityManager();
        try {
            em.getTransaction().begin();
            int affected = em.createNativeQuery(sql).executeUpdate();
            em.getTransaction().commit();
            return affected;
        } finally {
            em.close();
        }
    }

    private List<OutboxEvent> findRequestOutbox() {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT e FROM OutboxEvent e WHERE e.eventType = :type", OutboxEvent.class)
                    .setParameter("type", REQUEST_EVENT_TYPE)
                    .getResultList();
        } finally {
            em.close();
        }
    }

    private String refunded(String result, String failureCode, LocalDateTime resolvedAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", ORDER_ID);
        payload.put("userId", 42L);
        payload.put("result", result);
        payload.put("refundedAmount", 1_000L);
        payload.put("failureCode", failureCode);
        payload.put("resolvedAt", resolvedAt.toString());
        return envelope(payload);
    }

    private String envelope(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(new KafkaEventEnvelope(
                    UUID.randomUUID().toString(), "payment.refunded", LocalDateTime.now(), payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 결제됐으나 재고가 확정되지 않은 상태 — confirm() 이 보상 경로로 가는 원장. */
    private void seedReleasedReservation() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.createNativeQuery("""
                        INSERT INTO stock_reservations (order_id, status, items, created_at)
                        VALUES (?1, 'RELEASED', '[]', NOW(6))
                        """)
                .setParameter(1, ORDER_ID)
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
    }

    /** 감지 marker 만 있고 요청 Outbox 는 없는 상태 — ④-a 배포 이후 쌓인 잔여분을 모사한다. */
    private void seedCompensatedReservationWithoutRequest() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.createNativeQuery("""
                        INSERT INTO stock_reservations (order_id, status, items, compensated_at, created_at)
                        VALUES (?1, 'RELEASED', '[]', NOW(6), NOW(6))
                        """)
                .setParameter(1, ORDER_ID)
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
    }
}
