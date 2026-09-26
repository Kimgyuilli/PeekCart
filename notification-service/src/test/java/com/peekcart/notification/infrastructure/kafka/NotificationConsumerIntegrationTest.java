package com.peekcart.notification.infrastructure.kafka;

import com.peekcart.global.idempotency.ProcessedEvent;
import com.peekcart.global.idempotency.ProcessedEventJpaRepository;
import com.peekcart.notification.domain.model.Notification;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.notification.infrastructure.NotificationJpaRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * NotificationConsumer 멱등성 통합 테스트 (root 에서 peel 된 notification 소비 경로 검증).
 * <p>과도기 공유 DB: notification-service flyway 는 런타임 disabled, 테스트는 @TestPropertySource 로
 * 공유 V1~V4(:common classpath:db/migration)를 1회 적용한다(게이트 f).
 */
@SpringBootTest
@Import({IntegrationTestConfig.class, SharedContainers.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // ADR-0029: 리스너는 테스트에서 기본 off 다. 실제 Kafka 왕복으로 NotificationConsumer 소비·멱등성을 검증하는 것이 이 클래스의 대상이다.
        "app.kafka.listener.enabled=true"
})
// 켠 자율 writer 의 수명을 자기 클래스에 가둔다 (ADR-0029 D3) — context 가 캐시된 채 남으면
// 리스너가 이후 클래스의 공유 브로커·DB 를 계속 고친다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("NotificationConsumer 멱등성 통합 테스트")
class NotificationConsumerIntegrationTest extends AbstractIntegrationTest {

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired NotificationJpaRepository notificationJpaRepository;
    @Autowired ProcessedEventJpaRepository processedEventJpaRepository;
    @Autowired NotificationConsumer notificationConsumer;

    // DB-per-service(구현 ② PR2): notification 스키마에 users 테이블이 없고 교차 FK 도 제거됨(V13) →
    // 실제 user 행을 시드하지 않고 임의 userId(ID 참조)만 사용한다.
    private final Long userId = 42L;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("order.created 이벤트 소비 시 알림을 1건 생성한다")
    void orderCreated_createsNotification() {
        String eventId = UUID.randomUUID().toString();
        kafkaTemplate.send("order.created", userId.toString(), orderCreatedMessage(eventId));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Notification> notifications = notificationJpaRepository
                    .findByUserId(userId, PageRequest.of(0, 10)).getContent();
            assertThat(notifications).anyMatch(n -> n.getType() == NotificationType.ORDER_CREATED);
        });

        List<ProcessedEvent> processed = processedEventJpaRepository.findAll().stream()
                .filter(pe -> pe.getEventId().equals(eventId))
                .toList();
        assertThat(processed)
                .extracting(ProcessedEvent::getConsumerGroup)
                .containsExactly("notification-svc-order-created-group");
    }

    @Test
    @DisplayName("동일 eventId 를 2회 소비해도 알림은 1건만 생성된다 (consumer 멱등성)")
    void duplicateEvent_processedOnce() throws Exception {
        String eventId = UUID.randomUUID().toString();
        kafkaTemplate.send("order.created", userId.toString(), orderCreatedMessage(eventId)).get(10, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationJpaRepository.findByUserId(userId, PageRequest.of(0, 10)).getTotalElements())
                        .isEqualTo(1));

        // 동일 eventId 재전송 — broker 전달 완료(.get())까지 보장한 뒤 멱등성 확인
        kafkaTemplate.send("order.created", userId.toString(), orderCreatedMessage(eventId)).get(10, TimeUnit.SECONDS);

        // 충분히 대기 후에도 알림 수 변화 없음 (재전송이 소비되어도 중복 생성 없음)
        await().during(3, TimeUnit.SECONDS).atMost(6, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(notificationJpaRepository.findByUserId(userId, PageRequest.of(0, 10)).getTotalElements())
                        .isEqualTo(1));
    }

    @Test
    @DisplayName("[SAGA-REFUND-RESULT-NOTIFICATION-SUCCEEDED] payment.refunded(SUCCEEDED) 소비 시 환불 완료 알림을 1건 생성한다 (ADR-0018 D6)")
    void paymentRefundedSucceeded_createsNotification() {
        String eventId = UUID.randomUUID().toString();
        kafkaTemplate.send("payment.refunded", "9001", refundedMessage(eventId, 9001L, "SUCCEEDED"));

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(refundNotifications()).hasSize(1));
    }

    /**
     * 총 개수만 세면 "FAILED 가 잘못 알림을 만들고 SUCCEEDED 를 누락"해도 1건이라 통과한다.
     * 살아남은 알림이 <b>어느 주문의 것인지</b>까지 단언해야 ADR-0018 D6 을 실제로 증명한다.
     */
    @Test
    @DisplayName("[SAGA-REFUND-RESULT-NOTIFICATION-FAILED] payment.refunded(FAILED) 는 사용자에게 알리지 않는다 — 내부 미결을 전가하지 않는다")
    void paymentRefundedFailed_createsNoNotification() throws Exception {
        kafkaTemplate.send("payment.refunded", "9002",
                        refundedMessage(UUID.randomUUID().toString(), 9002L, "FAILED"))
                .get(10, TimeUnit.SECONDS);

        // 뒤이어 보낸 성공 회신이 도착하면 실패 회신은 이미 소비가 끝났다는 뜻이다 — 실패분이
        // 알림을 만들지 않았음을 고정 대기 없이 판정할 수 있다.
        kafkaTemplate.send("payment.refunded", "9003",
                        refundedMessage(UUID.randomUUID().toString(), 9003L, "SUCCEEDED"))
                .get(10, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(refundNotifications()).hasSize(1));

        // 살아남은 1건은 9003(성공)이어야 하고, 9002(실패)와 연결된 알림은 0건이어야 한다
        assertThat(refundNotifications().get(0).getMessage()).contains("9003").doesNotContain("9002");
    }

    /**
     * {@code UNRESOLVED} 는 <b>결과가 확정되지 않았다</b>는 뜻이다 — 실패도 성공도 아니다.
     * Payment 가 나중에 확정해 다시 회신하므로, 이 시점에 사용자에게 무엇이든 알리면
     * 그 알림은 뒤집힐 수 있다(ADR-0018 D4: 어느 소비자도 UNRESOLVED 로 전이하지 않는다).
     *
     * <p>FAILED 검사와 같은 방식으로 <b>고정 대기 없이</b> 판정한다 — 뒤이어 보낸 성공 회신이
     * 도착하면 앞의 UNRESOLVED 는 이미 소비가 끝났다는 뜻이다.
     */
    @Test
    @DisplayName("[SAGA-REFUND-RESULT-NOTIFICATION-UNRESOLVED] payment.refunded(UNRESOLVED) 는 알리지 않는다 — 확정되지 않은 결과를 통지하면 뒤집힌다")
    void paymentRefundedUnresolved_createsNoNotification() throws Exception {
        kafkaTemplate.send("payment.refunded", "9004",
                        refundedMessage(UUID.randomUUID().toString(), 9004L, "UNRESOLVED"))
                .get(10, TimeUnit.SECONDS);

        kafkaTemplate.send("payment.refunded", "9005",
                        refundedMessage(UUID.randomUUID().toString(), 9005L, "SUCCEEDED"))
                .get(10, TimeUnit.SECONDS);

        await().atMost(15, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(refundNotifications()).hasSize(1));

        assertThat(refundNotifications().get(0).getMessage()).contains("9005").doesNotContain("9004");
    }

    /**
     * 필드 검증은 <b>broker 를 거치지 않고</b> 본다. 잘못된 메시지를 실제로 발행하면 소비가
     * 재시도 백오프(1s/5s/30s)에 들어가 해당 파티션을 30초 넘게 점유하므로, 같은 토픽을 쓰는
     * 다른 단언이 그 뒤에 줄을 서게 된다 — 검증하려는 계약과 무관한 타이밍 의존이 생긴다.
     * 배선은 위 두 테스트가 이미 broker 왕복으로 고정했다.
     */
    @Test
    @DisplayName("userId 가 null·비숫자·0 이면 알림을 만들지 않고 예외로 재시도·DLQ 에 맡긴다")
    void paymentRefundedWithInvalidUserId_createsNoNotification() {
        for (String badUserId : List.of("null", "\"not-a-number\"", "0")) {
            String malformed = """
                    {"eventId":"%s","eventType":"payment.refunded","payload":{"orderId":9004,\
                    "userId":%s,"result":"SUCCEEDED","refundedAmount":50000,\
                    "resolvedAt":"2026-01-01T00:00:00"}}
                    """.formatted(UUID.randomUUID().toString(), badUserId);

            assertThatThrownBy(() -> notificationConsumer.handlePaymentRefunded(malformed))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(refundNotifications()).isEmpty();
    }

    private List<Notification> refundNotifications() {
        return notificationJpaRepository.findByUserId(userId, PageRequest.of(0, 10)).getContent().stream()
                .filter(n -> n.getType() == NotificationType.PAYMENT_REFUNDED)
                .toList();
    }

    private String refundedMessage(String eventId, long orderId, String result) {
        return """
                {"eventId":"%s","eventType":"payment.refunded","payload":{"orderId":%d,"userId":%d,\
                "result":"%s","refundedAmount":50000,"failureCode":null,"resolvedAt":"2026-01-01T00:00:00"}}
                """.formatted(eventId, orderId, userId, result);
    }

    private String orderCreatedMessage(String eventId) {
        return """
                {"eventId":"%s","payload":{"userId":%d,"orderNumber":"ORD-%s","totalAmount":50000}}
                """.formatted(eventId, userId, eventId.substring(0, 8));
    }

}
