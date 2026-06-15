package com.peekcart.notification.infrastructure.kafka;

import com.peekcart.global.idempotency.ProcessedEvent;
import com.peekcart.global.idempotency.ProcessedEventJpaRepository;
import com.peekcart.notification.domain.model.Notification;
import com.peekcart.notification.domain.model.NotificationType;
import com.peekcart.notification.infrastructure.NotificationJpaRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * NotificationConsumer 멱등성 통합 테스트 (root 에서 peel 된 notification 소비 경로 검증).
 * <p>과도기 공유 DB: notification-service flyway 는 런타임 disabled, 테스트는 @TestPropertySource 로
 * 공유 V1~V4(:common classpath:db/migration)를 1회 적용한다(게이트 f).
 */
@SpringBootTest
@Testcontainers
@Import(IntegrationTestConfig.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("NotificationConsumer 멱등성 통합 테스트")
class NotificationConsumerIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7")
            .withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired NotificationJpaRepository notificationJpaRepository;
    @Autowired ProcessedEventJpaRepository processedEventJpaRepository;

    private Long userId;

    @BeforeEach
    void setUp() {
        cleanDatabase();
        userId = insertUser();
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

    private String orderCreatedMessage(String eventId) {
        return """
                {"eventId":"%s","payload":{"userId":%d,"orderNumber":"ORD-%s","totalAmount":50000}}
                """.formatted(eventId, userId, eventId.substring(0, 8));
    }

    private Long insertUser() {
        EntityManager em = emf.createEntityManager();
        em.getTransaction().begin();
        em.createNativeQuery("""
                INSERT INTO users (email, password_hash, name, role, created_at, updated_at)
                VALUES (:email, 'hashed', '테스트유저', 'USER', :now, :now)
                """)
                .setParameter("email", "noti-" + UUID.randomUUID() + "@peekcart.com")
                .setParameter("now", LocalDateTime.now())
                .executeUpdate();
        Long id = ((Number) em.createNativeQuery("SELECT LAST_INSERT_ID()").getSingleResult()).longValue();
        em.getTransaction().commit();
        em.close();
        return id;
    }
}
