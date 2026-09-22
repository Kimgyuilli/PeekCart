package com.peekcart.order.infrastructure.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxPollingScheduler;
import com.peekcart.global.outbox.OutboxEventRepository;
import com.peekcart.global.outbox.dto.KafkaEventEnvelope;
import com.peekcart.order.domain.model.Order;
import com.peekcart.order.domain.model.OrderItemData;
import com.peekcart.order.domain.model.OrderStatus;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.willThrow;

/**
 * {@code order.cancelled} 발행이 주문 취소와 <b>같은 트랜잭션</b>에서 일어나는지 검증한다 (계획 P7·§5.2).
 *
 * <p>단위 테스트는 {@code @InjectMocks} 객체를 직접 호출하므로 Spring 프록시도 DB 트랜잭션도 없다 —
 * 발행 호출이 있었다는 사실만 볼 수 있고, 취소와 Outbox 가 <i>함께</i> 커밋/롤백되는지는 증명하지
 * 못한다(GW-2 #2, false-green). 여기서는 실제 컨텍스트/DB 로 다음을 본다.
 * <ul>
 *   <li>성공: {@code CANCELLED} + {@code order.cancelled} Outbox 1행이 함께 커밋되고 payload 가 계약대로다</li>
 *   <li>실패 주입: Outbox 저장이 실패하면 주문 상태와 {@code processed_events} 까지 전부 롤백된다</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("order.cancelled 이벤트 계약 통합 테스트 (동일 트랜잭션·payload)")
class OrderCancelledEventContractIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrderEventConsumer consumer;
    @Autowired ObjectMapper objectMapper;

    @MockitoSpyBean OutboxEventRepository outboxEventRepository;

    /**
     * Outbox poller 를 무력화한다.
     *
     * <p>{@code OutboxEventRepository} 를 context-wide spy 로 바꾸는 순간, **5초마다 도는**
     * {@link OutboxPollingScheduler} 가 그 spy 를 호출하게 된다
     * ({@code OutboxPollingService:71} 의 {@code findPendingEvents}). 그런데 Mockito 의
     * {@code willThrow(...).given(spy).save(...)} 는 <b>두 단계</b>라, 그 사이에 다른 스레드가
     * spy 를 건드리면 {@code UnfinishedStubbingException} 이 난다 — 코드가 아니라
     * <b>주사위</b>가 결정하는 실패다(CI 에서 실제로 터졌다).
     *
     * <p>이 테스트가 보는 것은 "취소와 Outbox 저장이 같은 트랜잭션인가" 이고 발행 여부가 아니다.
     * poller 를 no-op mock 으로 대체하면 경합이 사라지고 검증 대상은 그대로 남는다.
     */
    @MockitoBean OutboxPollingScheduler outboxPollingScheduler;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("payment.failed → CANCELLED 와 order.cancelled Outbox 1행이 함께 커밋되고 items·reason 이 실린다")
    void paymentFailed_cancelAndOutboxCommitTogether() throws Exception {
        Long orderId = seedPendingOrder();
        String eventId = UUID.randomUUID().toString();

        assertThatCode(() -> consumer.handlePaymentFailed(paymentFailed(orderId, eventId)))
                .doesNotThrowAnyException();

        assertThat(currentStatus(orderId)).isEqualTo(OrderStatus.CANCELLED);

        List<OutboxEvent> cancelled = findOutbox(orderId);
        assertThat(cancelled).hasSize(1);

        JsonNode payload = objectMapper.readTree(cancelled.get(0).getPayload()).get("payload");
        assertThat(payload.get("reason").asText()).isEqualTo("PAYMENT_FAILED");
        assertThat(payload.get("orderId").asLong()).isEqualTo(orderId);
        // items[] 는 실제 주문 품목과 일치해야 한다 (계획 §5.2 P5)
        assertThat(payload.get("items")).hasSize(1);
        assertThat(payload.get("items").get(0).get("productId").asLong()).isEqualTo(100L);
        assertThat(payload.get("items").get(0).get("quantity").asInt()).isEqualTo(2);
        assertThat(countProcessedEvents(eventId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("Outbox 저장 실패 주입 → 주문 상태·processed_events 전부 롤백 (부분 커밋 없음)")
    void outboxSaveFailure_rollsBackEverything() {
        Long orderId = seedPendingOrder();
        String eventId = UUID.randomUUID().toString();
        willThrow(new IllegalStateException("주입된 Outbox 저장 실패"))
                .given(outboxEventRepository).save(any(OutboxEvent.class));

        // Spring 이 저장소 예외를 DataAccessException 으로 번역하므로 타입이 아니라 메시지로 식별한다
        assertThatThrownBy(() -> consumer.handlePaymentFailed(paymentFailed(orderId, eventId)))
                .hasMessageContaining("주입된 Outbox 저장 실패");

        assertThat(currentStatus(orderId)).isEqualTo(OrderStatus.PENDING);
        assertThat(findOutbox(orderId)).isEmpty();
        assertThat(countProcessedEvents(eventId)).isZero();
    }

    private List<OutboxEvent> findOutbox(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.createQuery(
                            "SELECT e FROM OutboxEvent e WHERE e.eventType = 'order.cancelled' "
                                    + "AND e.aggregateId = :aggregateId", OutboxEvent.class)
                    .setParameter("aggregateId", orderId.toString())
                    .getResultList();
        } finally {
            em.close();
        }
    }

    private long countProcessedEvents(String eventId) {
        EntityManager em = emf.createEntityManager();
        try {
            return ((Number) em.createNativeQuery(
                            "SELECT COUNT(*) FROM processed_events WHERE event_id = ?1")
                    .setParameter(1, eventId)
                    .getSingleResult()).longValue();
        } finally {
            em.close();
        }
    }

    private OrderStatus currentStatus(Long orderId) {
        EntityManager em = emf.createEntityManager();
        try {
            return em.find(Order.class, orderId).getStatus();
        } finally {
            em.close();
        }
    }

    private String paymentFailed(Long orderId, String eventId) {
        try {
            return objectMapper.writeValueAsString(new KafkaEventEnvelope(
                    eventId, "payment.failed", LocalDateTime.now(), Map.of("orderId", orderId)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Long seedPendingOrder() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        Order order = Order.create(42L, "ORD-" + UUID.randomUUID(), "받는이", "01000000000", "12345", "주소",
                List.of(new OrderItemData(100L, 2, 1_000L)));
        em.persist(order);
        em.getTransaction().commit();
        Long id = order.getId();
        em.close();
        return id;
    }
}
