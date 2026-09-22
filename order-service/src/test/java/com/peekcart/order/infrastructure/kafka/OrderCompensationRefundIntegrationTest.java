package com.peekcart.order.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.OutboxPollingScheduler;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.order.domain.model.CompensationReason;
import com.peekcart.order.domain.model.CompensationStatus;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderCompensation;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.repository.OrderCompensationRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Order 측 환불 보상 계약 통합 테스트 (계획 P14 — ④-c-1b).
 *
 * <p>세 가지를 본다.
 * <ul>
 *   <li><b>P11 원자성</b> — 감지 원장과 요청 Outbox 가 같은 트랜잭션이다. 부분 커밋은
 *       "감지했는데 아무도 환불하지 않는" 영구 미결을 만든다(ADR-0018 D1)</li>
 *   <li><b>P12 종결</b> — 회신 결과별로 종착이 다르다. {@code FAILED} 를 {@code RESOLVED} 로 닫으면
 *       그 원장은 거짓말을 한다(D4)</li>
 *   <li><b>P11 backfill 멱등</b> — 재실행 시 추가 발행 0. 이 보장은 <b>producer DB 안의</b>
 *       {@code NOT EXISTS} 조건으로만 성립한다(Payment 의 UNIQUE 는 DB 경계를 넘지 못한다)</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("Order 환불 보상 계약 통합 테스트")
class OrderCompensationRefundIntegrationTest extends AbstractIntegrationTest {

    private static final String REQUEST_EVENT_TYPE = "order.compensation.requested";

    @Autowired OrderEventConsumer consumer;
    @Autowired OrderCompensationRepository compensationRepository;
    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;

    @MockitoSpyBean OutboxEventRepository outboxEventRepository;

    /**
     * Outbox poller 를 무력화한다 — 두 가지를 동시에 막는다.
     *
     * <p>① {@code OutboxEventRepository} 를 context-wide spy 로 바꾸면 **5초마다 도는**
     * {@link OutboxPollingScheduler} 가 그 spy 를 호출한다. Mockito 의
     * {@code willThrow(...).given(spy)...} 는 두 단계라 그 사이에 다른 스레드가 spy 를 건드리면
     * {@code UnfinishedStubbingException} 이 난다 — 코드가 아니라 <b>주사위</b>가 결정하는 실패다.
     *
     * <p>② 실제 poller 가 돌면 {@code PENDING} 행을 집어 {@code PUBLISHED} 로 바꾼다. 이 클래스의
     * backfill 검증은 상태 카운트를 단언하므로 그 자체로 경합이다.
     *
     * <p>발행 경로가 필요한 검사는 {@code kafkaTemplate.send} 로 직접 넣으므로 poller 는 쓰이지 않는다.
     */
    @MockitoBean OutboxPollingScheduler outboxPollingScheduler;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    // ---------------- P11: 트리거 발행 + 원자성 ----------------

    @Test
    @DisplayName("감지 시 order.compensation.requested 가 원장과 함께 커밋되고 키·payload 가 계약대로다")
    void detection_publishesRequestWithContractKeys() throws Exception {
        Long orderId = seedCancelledOrder();

        consumer.handlePaymentCompleted(paymentCompleted(orderId, UUID.randomUUID().toString()));

        List<OutboxEvent> requests = findRequestOutbox(orderId);
        assertThat(requests).hasSize(1);
        OutboxEvent event = requests.get(0);
        // aggregateType/aggregateId 표 고정 — backfill SQL 의 NOT EXISTS 가 같은 키를 쓴다(계획 §2.1-o)
        assertThat(event.getAggregateType()).isEqualTo("ORDER");
        assertThat(event.getAggregateId()).isEqualTo(orderId.toString());

        JsonNode payload = objectMapper.readTree(event.getPayload()).get("payload");
        assertThat(payload.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(payload.get("reason").asText()).isEqualTo("PAID_BUT_CANCELLED");
        // detectedAt 은 원장의 감지 시각과 정확히 같아야 한다. 근사 비교로 눅이지 않는 이유:
        // 원장은 DB 왕복을 거치고 payload 는 인메모리 값이라, 둘이 등식이 되려면 감지 시각이 애초에
        // 저장소 정밀도(DATETIME(6))로 확정돼 있어야 한다. 단언을 마이크로초로 절삭해 맞추면
        // MySQL 의 반올림(truncate 아님)으로 1μs 어긋나는 경우를 이 테스트가 못 잡는다.
        OrderCompensation ledger = compensationRepository
                .findByOrderIdAndReason(orderId, CompensationReason.PAID_BUT_CANCELLED).orElseThrow();
        assertThat(LocalDateTime.parse(payload.get("detectedAt").asText()))
                .isEqualTo(ledger.getDetectedAt());
    }

    @Test
    @DisplayName("요청 Outbox 저장이 실패하면 보상 원장도 함께 롤백된다 — 부분 커밋 없음")
    void outboxFailure_rollsBackCompensationLedger() {
        Long orderId = seedCancelledOrder();
        willThrow(new RuntimeException("outbox down")).given(outboxEventRepository).save(any());

        assertThatThrownBy(() -> consumer.handlePaymentCompleted(
                paymentCompleted(orderId, UUID.randomUUID().toString())))
                .isInstanceOf(RuntimeException.class);

        assertThat(compensationRepository.findByOrderIdAndReason(orderId, CompensationReason.PAID_BUT_CANCELLED))
                .isEmpty();
        assertThat(countProcessedEvents()).isZero();
    }

    @Test
    @DisplayName("재소비(새 eventId)에도 요청은 1건 — 원장이 이미 있으면 재발행하지 않는다")
    void reconsume_doesNotRepublishRequest() {
        Long orderId = seedCancelledOrder();

        consumer.handlePaymentCompleted(paymentCompleted(orderId, UUID.randomUUID().toString()));
        consumer.handlePaymentCompleted(paymentCompleted(orderId, UUID.randomUUID().toString()));

        assertThat(findRequestOutbox(orderId)).hasSize(1);
    }

    // ---------------- P12: 회신 소비 종결 ----------------

    @Test
    @DisplayName("[SAGA-REFUND-RESULT-ORDER-SUCCEEDED] payment.refunded(SUCCEEDED) → RESOLVED (④-a R-2 가 닫히는 경로)")
    void refundSucceeded_resolvesLedger() {
        Long orderId = seedOpenCompensation();
        LocalDateTime resolvedAt = LocalDateTime.now().withNano(0);

        consumer.handlePaymentRefunded(refunded(orderId, "SUCCEEDED", null, resolvedAt));

        OrderCompensation ledger = reload(orderId);
        assertThat(ledger.getStatus()).isEqualTo(CompensationStatus.RESOLVED);
        assertThat(ledger.getResolvedAt()).isEqualTo(resolvedAt);
        assertThat(ledger.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("[SAGA-REFUND-RESULT-ORDER-FAILED] payment.refunded(FAILED) → REFUND_FAILED + failure_code — '해결됨'이 아니다")
    void refundFailed_closesAsUnresolvedTerminal() {
        Long orderId = seedOpenCompensation();

        consumer.handlePaymentRefunded(
                refunded(orderId, "FAILED", "NOT_CANCELABLE_AMOUNT", LocalDateTime.now().withNano(0)));

        OrderCompensation ledger = reload(orderId);
        assertThat(ledger.getStatus()).isEqualTo(CompensationStatus.REFUND_FAILED);
        assertThat(ledger.getFailureCode()).isEqualTo("NOT_CANCELABLE_AMOUNT");
    }

    @Test
    @DisplayName("[SAGA-REFUND-RESULT-ORDER-UNRESOLVED] payment.refunded(UNRESOLVED) → 전이하지 않는다 (OPEN 유지)")
    void refundUnresolved_doesNotTransition() {
        Long orderId = seedOpenCompensation();

        consumer.handlePaymentRefunded(refunded(orderId, "UNRESOLVED", null, LocalDateTime.now().withNano(0)));

        assertThat(reload(orderId).getStatus()).isEqualTo(CompensationStatus.OPEN);
    }

    @Test
    @DisplayName("종결 후 도착한 회신은 종착 상태를 덮지 않는다 — 회신 재전달 내성")
    void redeliveredReply_doesNotOverwriteTerminal() {
        Long orderId = seedOpenCompensation();
        LocalDateTime first = LocalDateTime.now().withNano(0);

        consumer.handlePaymentRefunded(refunded(orderId, "SUCCEEDED", null, first));
        consumer.handlePaymentRefunded(
                refunded(orderId, "FAILED", "LATE", first.plusHours(1)));

        OrderCompensation ledger = reload(orderId);
        assertThat(ledger.getStatus()).isEqualTo(CompensationStatus.RESOLVED);
        assertThat(ledger.getResolvedAt()).isEqualTo(first);
        assertThat(ledger.getFailureCode()).isNull();
    }

    @Test
    @DisplayName("resolvedAt·failureCode 가 없는 구 메시지도 소비를 깨뜨리지 않는다")
    void legacyMessageWithoutOptionalFields_tolerated() {
        Long orderId = seedOpenCompensation();
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("result", "SUCCEEDED");

        assertThatCode(() -> consumer.handlePaymentRefunded(envelope("payment.refunded", payload)))
                .doesNotThrowAnyException();

        OrderCompensation ledger = reload(orderId);
        assertThat(ledger.getStatus()).isEqualTo(CompensationStatus.RESOLVED);
        assertThat(ledger.getResolvedAt()).isNotNull();
    }

    @Test
    @DisplayName("Order 원장이 없는 회신(다른 감지 지점발)은 예외 없이 no-op")
    void replyWithoutLedger_isNoop() {
        assertThatCode(() -> consumer.handlePaymentRefunded(
                refunded(999_999L, "SUCCEEDED", null, LocalDateTime.now().withNano(0))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("failureCode 가 없는 FAILED 회신은 UNKNOWN 으로 정규화한다 — 근거 없는 종착 금지")
    void failedReplyWithoutCode_normalizedToUnknown() {
        Long orderId = seedOpenCompensation();
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("result", "FAILED");
        payload.put("resolvedAt", LocalDateTime.now().withNano(0).toString());

        consumer.handlePaymentRefunded(envelope("payment.refunded", payload));

        OrderCompensation ledger = reload(orderId);
        assertThat(ledger.getStatus()).isEqualTo(CompensationStatus.REFUND_FAILED);
        assertThat(ledger.getFailureCode()).isEqualTo("UNKNOWN");
    }

    // ---------------- P14: listener 배선 (실제 Kafka 왕복) ----------------

    /**
     * 위 회신 테스트들은 listener 를 직접 호출하므로 토픽명·group·factory 배선이 틀려도 통과한다.
     * 배선 계약은 여기서 실제 broker 왕복으로 고정한다.
     */
    @Test
    @DisplayName("실제 Kafka 발행 → payment.refunded listener 가 배선되어 원장을 종결한다")
    void refundReplyListener_isWiredToBroker() {
        Long orderId = seedOpenCompensation();

        kafkaTemplate.send("payment.refunded", orderId.toString(),
                refunded(orderId, "SUCCEEDED", null, LocalDateTime.now().withNano(0)));

        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(reload(orderId).getStatus()).isEqualTo(CompensationStatus.RESOLVED));
    }

    // ---------------- P11: backfill 멱등 ----------------

    @Test
    @DisplayName("backfill DML 2회 실행 → 2회차 0건 (멱등 근거는 producer DB 의 NOT EXISTS)")
    void backfill_isIdempotentAcrossReruns() {
        Long orderId = seedOpenCompensation();   // 감지만 있고 요청 Outbox 는 없는 상태(④-a 배포 잔여분)

        assertThat(runBackfillStatement(0)).isEqualTo(1);
        assertThat(runBackfillStatement(0)).isZero();
        runBackfillStatement(1);
        assertThat(findRequestOutbox(orderId)).hasSize(1);
    }

    /**
     * <b>c1:5/c2:1/c3:1 (P1) 회귀</b> — 1단계와 2단계 사이의 행이 {@code PENDING} 이면 롤링 배포 중
     * 구 poller 가 <b>필수 필드 없는 payload('{}') 를 발행하고 PUBLISHED 로 봉인</b>해 요청이 영구
     * 유실된다. 조립 중에는 발행 대상 상태가 아니어야 한다.
     */
    @Test
    @DisplayName("backfill 1단계 행은 poller 에 노출되지 않는다 (PENDING 아님) — 2단계가 함께 전환")
    void backfillIntermediateRow_isNotPollable() {
        seedOpenCompensation();

        runBackfillStatement(0);
        assertThat(countByStatus("PENDING")).as("조립 중 행이 발행 대상이면 안 된다").isZero();
        assertThat(countByStatus("BACKFILL")).isEqualTo(1L);
        assertThat(outboxEventRepository.findPendingEvents(100)).isEmpty();

        runBackfillStatement(1);
        assertThat(countByStatus("PENDING")).isEqualTo(1L);
        assertThat(countByStatus("BACKFILL")).isZero();
    }

    /** 2단계 전에 실패해도 재실행이 안전하다 — 1단계는 NOT EXISTS 로 건너뛰고 2단계가 이어 채운다. */
    @Test
    @DisplayName("1·2단계 사이에서 중단된 뒤 재실행해도 중복 없이 복구된다")
    void backfill_recoversAfterInterruptionBetweenStages() {
        Long orderId = seedOpenCompensation();

        runBackfillStatement(0);          // 여기서 중단됐다고 가정
        assertThat(runBackfillStatement(0)).isZero();   // 재실행: 1단계는 건너뛴다
        runBackfillStatement(1);          // 2단계가 남은 BACKFILL 행을 이어 채운다

        assertThat(findRequestOutbox(orderId)).hasSize(1);
        assertThat(countByStatus("BACKFILL")).isZero();
    }

    @Test
    @DisplayName("backfill 2단계가 envelope 을 채우고 eventId 가 outbox_events.event_id 와 일치한다")
    void backfill_fillsEnvelopeWithMatchingEventId() throws Exception {
        Long orderId = seedOpenCompensation();
        runBackfillStatement(0);
        runBackfillStatement(1);

        OutboxEvent event = findRequestOutbox(orderId).get(0);
        JsonNode envelope = objectMapper.readTree(event.getPayload());
        // eventId 를 두 자리에 넣어야 해서 2단계로 나눴다 — 두 값이 갈라지면 소비 멱등 키가 흔들린다
        assertThat(envelope.get("eventId").asText()).isEqualTo(event.getEventId());
        assertThat(envelope.get("eventType").asText()).isEqualTo(REQUEST_EVENT_TYPE);
        assertThat(envelope.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(envelope.get("payload").get("orderId").asLong()).isEqualTo(orderId);
        assertThat(envelope.get("payload").get("reason").asText()).isEqualTo("PAID_BUT_CANCELLED");
    }

    // ---------------- helpers ----------------

    /**
     * <b>운영 마이그레이션 V5 의 backfill DML 을 그대로 읽어 실행한다.</b>
     *
     * <p>SQL 을 테스트에 복제하면 실제 V5 가 틀리거나 사라져도 복제본만 맞으면 통과한다 — 검증하는
     * 대상이 마이그레이션이 아니라 복제본이 되는 false-green 이다. 파일이 단일 SSOT 이므로
     * V5 를 고치면 이 테스트가 자동으로 새 SQL 을 실행한다.
     *
     * @param index 0 = 1단계(INSERT), 1 = 2단계(UPDATE)
     */
    private int runBackfillStatement(int index) {
        return executeUpdate(backfillStatements().get(index));
    }

    /** V5 에서 DML(INSERT/UPDATE) 문장만 추출한다. DDL 은 Flyway 가 이미 적용했으므로 제외한다. */
    private List<String> backfillStatements() {
        String sql;
        try {
            sql = new String(getClass().getResourceAsStream(
                    "/db/migration/V5__order_compensation_refund_closure.sql").readAllBytes(), UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("V5 마이그레이션을 읽을 수 없습니다", e);
        }
        List<String> statements = Arrays.stream(stripComments(sql).split(";"))
                .map(String::trim)
                .filter(st -> st.startsWith("INSERT") || st.startsWith("UPDATE"))
                .toList();
        assertThat(statements).as("V5 의 backfill DML 은 2문장이어야 한다").hasSize(2);
        return statements;
    }

    private String stripComments(String sql) {
        return Arrays.stream(sql.split("\n"))
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
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

    private List<OutboxEvent> findRequestOutbox(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery("SELECT e FROM OutboxEvent e WHERE e.aggregateId = :id "
                            + "AND e.eventType = :type", OutboxEvent.class)
                    .setParameter("id", orderId.toString())
                    .setParameter("type", REQUEST_EVENT_TYPE)
                    .getResultList();
        } finally {
            em.close();
        }
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

    private long countProcessedEvents() {
        EntityManager em = emf.createEntityManager();
        try {
            return ((Number) em.createNativeQuery("SELECT COUNT(*) FROM processed_events")
                    .getSingleResult()).longValue();
        } finally {
            em.close();
        }
    }

    private OrderCompensation reload(Long orderId) {
        Optional<OrderCompensation> found = compensationRepository
                .findByOrderIdAndReason(orderId, CompensationReason.PAID_BUT_CANCELLED);
        return found.orElseThrow();
    }

    private String refunded(Long orderId, String result, String failureCode, LocalDateTime resolvedAt) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("userId", 42L);
        payload.put("result", result);
        payload.put("refundedAmount", 1_000L);
        payload.put("failureCode", failureCode);
        payload.put("resolvedAt", resolvedAt.toString());
        return envelope("payment.refunded", payload);
    }

    private String envelope(String eventType, Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(new KafkaEventEnvelope(
                    UUID.randomUUID().toString(), eventType, LocalDateTime.now(), payload));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String paymentCompleted(Long orderId, String eventId) {
        try {
            return objectMapper.writeValueAsString(new KafkaEventEnvelope(
                    eventId, "payment.completed", LocalDateTime.now(), Map.of("orderId", orderId)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 감지만 있고 요청 Outbox 는 없는 상태 — ④-a 배포 이후 쌓인 잔여분을 모사한다. */
    private Long seedOpenCompensation() {
        Long orderId = seedCancelledOrder();
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.persist(OrderCompensation.open(orderId, CompensationReason.PAID_BUT_CANCELLED, "테스트 시드"));
        em.getTransaction().commit();
        em.close();
        return orderId;
    }

    private Long seedCancelledOrder() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Order order = Order.create(42L, "ORD-" + UUID.randomUUID(), "받는이", "01000000000", "12345", "주소",
                List.of(new OrderItemData(100L, 1, 1_000L)));
        em.persist(order);
        em.flush();
        Long id = order.getId();
        em.createNativeQuery("UPDATE orders SET status = 'CANCELLED' WHERE id = ?1")
                .setParameter(1, id)
                .executeUpdate();
        em.getTransaction().commit();
        em.close();
        return id;
    }
}
