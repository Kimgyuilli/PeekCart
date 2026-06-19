package com.peekcart.payment.domain.repository;

import com.peekcart.payment.domain.model.WebhookLog;

public interface WebhookLogRepository {

    WebhookLog save(WebhookLog webhookLog);

    boolean existsByIdempotencyKey(String idempotencyKey);
}
