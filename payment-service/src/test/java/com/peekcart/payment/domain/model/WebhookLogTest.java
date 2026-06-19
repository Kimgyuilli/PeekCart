package com.peekcart.payment.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("WebhookLog 도메인 단위 테스트")
class WebhookLogTest {

    @Test
    @DisplayName("create: 모든 필드가 설정된다")
    void create_setsAllFields() {
        WebhookLog log = WebhookLog.create("pk-123", "PAYMENT_DONE", "idem-key", "{}", "PROCESSED");

        assertThat(log.getPaymentKey()).isEqualTo("pk-123");
        assertThat(log.getEventType()).isEqualTo("PAYMENT_DONE");
        assertThat(log.getIdempotencyKey()).isEqualTo("idem-key");
        assertThat(log.getPayload()).isEqualTo("{}");
        assertThat(log.getStatus()).isEqualTo("PROCESSED");
        assertThat(log.getReceivedAt()).isNotNull();
    }
}
