package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.repository.WebhookLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
@RequiredArgsConstructor
public class WebhookLogRepositoryImpl implements WebhookLogRepository {
    private final WebhookLogJpaRepository webhookLogJpaRepository;

    @Override
    public int insertIfAbsent(String paymentKey, String eventType, String idempotencyKey, String payload, LocalDateTime now) {
        return webhookLogJpaRepository.insertIfAbsent(paymentKey, eventType, idempotencyKey, payload, now);
    }
}
