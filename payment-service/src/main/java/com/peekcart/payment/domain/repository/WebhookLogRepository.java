package com.peekcart.payment.domain.repository;

import java.time.LocalDateTime;

public interface WebhookLogRepository {
    /** 1 for the first admitted hint, 0 for its duplicate. All fields are bounded before admission. */
    int insertIfAbsent(String paymentKey, String eventType, String idempotencyKey, String payload, LocalDateTime now);
}
