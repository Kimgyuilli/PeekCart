package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.WebhookLog;
import com.peekcart.payment.domain.repository.WebhookLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class WebhookLogRepositoryImpl implements WebhookLogRepository {

    private final WebhookLogJpaRepository webhookLogJpaRepository;

    @Override
    public WebhookLog save(WebhookLog webhookLog) {
        return webhookLogJpaRepository.save(webhookLog);
    }

    @Override
    public boolean existsByIdempotencyKey(String idempotencyKey) {
        return webhookLogJpaRepository.existsByIdempotencyKey(idempotencyKey);
    }
}
