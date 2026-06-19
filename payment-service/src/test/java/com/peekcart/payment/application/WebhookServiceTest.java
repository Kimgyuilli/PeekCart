package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.WebhookLog;
import com.peekcart.payment.domain.repository.WebhookLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookService 단위 테스트")
class WebhookServiceTest {

    private static final String WEBHOOK_SECRET = "test-webhook-secret";

    @Mock WebhookLogRepository webhookLogRepository;
    private WebhookService webhookService;

    @BeforeEach
    void setUp() {
        webhookService = new WebhookService(webhookLogRepository, WEBHOOK_SECRET);
    }

    @Test
    @DisplayName("processWebhook: 유효한 서명이면 로그를 저장한다")
    void processWebhook_validSignature_savesLog() {
        String payload = "{\"paymentKey\":\"pk-123\"}";
        String signature = computeHmac(payload);
        given(webhookLogRepository.existsByIdempotencyKey("idem-1")).willReturn(false);

        assertThatCode(() -> webhookService.processWebhook(signature, "pk-123", "PAYMENT_DONE", "idem-1", payload))
                .doesNotThrowAnyException();

        then(webhookLogRepository).should().save(any(WebhookLog.class));
    }

    @Test
    @DisplayName("processWebhook: 중복 idempotencyKey이면 저장을 스킵한다")
    void processWebhook_duplicateKey_skips() {
        String payload = "{\"paymentKey\":\"pk-123\"}";
        String signature = computeHmac(payload);
        given(webhookLogRepository.existsByIdempotencyKey("idem-1")).willReturn(true);

        webhookService.processWebhook(signature, "pk-123", "PAYMENT_DONE", "idem-1", payload);

        then(webhookLogRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("processWebhook: 서명이 null이면 PAY-006 예외가 발생한다")
    void processWebhook_nullSignature_throwsPAY006() {
        assertThatThrownBy(() -> webhookService.processWebhook(null, "pk-123", "PAYMENT_DONE", "idem-1", "{}"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_006);
    }

    @Test
    @DisplayName("processWebhook: 서명이 불일치하면 PAY-006 예외가 발생한다")
    void processWebhook_invalidSignature_throwsPAY006() {
        assertThatThrownBy(() -> webhookService.processWebhook("invalid-sig", "pk-123", "PAYMENT_DONE", "idem-1", "{}"))
                .isInstanceOf(PaymentException.class)
                .extracting(e -> ((PaymentException) e).getErrorCode())
                .isEqualTo(ErrorCode.PAY_006);
    }

    private String computeHmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
