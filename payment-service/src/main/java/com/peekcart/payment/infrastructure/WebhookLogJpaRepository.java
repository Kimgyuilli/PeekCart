package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.WebhookLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookLogJpaRepository extends JpaRepository<WebhookLog, Long> {

    boolean existsByIdempotencyKey(String idempotencyKey);
}
