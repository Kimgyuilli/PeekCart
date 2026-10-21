package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.WebhookLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface WebhookLogJpaRepository extends JpaRepository<WebhookLog, Long> {
    @Modifying(flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO webhook_logs
                (payment_key, event_type, idempotency_key, payload, status, received_at)
            VALUES (:paymentKey, :eventType, :idempotencyKey, :payload, 'HINT_ACCEPTED', :now)
            """, nativeQuery = true)
    int insertIfAbsent(@Param("paymentKey") String paymentKey, @Param("eventType") String eventType,
                       @Param("idempotencyKey") String idempotencyKey, @Param("payload") String payload,
                       @Param("now") LocalDateTime now);
}
