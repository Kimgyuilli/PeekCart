package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.WebhookLog;
import com.peekcart.payment.domain.repository.WebhookLogRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Toss 웹훅 이벤트를 처리하는 애플리케이션 서비스.
 * HMAC-SHA256 서명 검증 및 idempotency_key 중복 방지를 담당한다.
 */
@Service
@Transactional
public class WebhookService {

    private final WebhookLogRepository webhookLogRepository;
    private final String webhookSecret;

    public WebhookService(WebhookLogRepository webhookLogRepository,
                          @Value("${toss.payments.webhook-secret}") String webhookSecret) {
        this.webhookLogRepository = webhookLogRepository;
        this.webhookSecret = webhookSecret;
    }

    /**
     * 서명을 검증하고, 웹훅을 처리하여 로그를 저장한다.
     * 이미 처리된 idempotencyKey면 스킵한다.
     *
     * @throws PaymentException 서명 불일치 시 {@code PAY-006}
     */
    public void processWebhook(String signature, String paymentKey, String eventType,
                               String idempotencyKey, String payload) {
        verifySignature(signature, payload);

        if (webhookLogRepository.existsByIdempotencyKey(idempotencyKey)) {
            return;
        }
        WebhookLog log = WebhookLog.create(paymentKey, eventType, idempotencyKey, payload, "PROCESSED");
        webhookLogRepository.save(log);
    }

    private void verifySignature(String signature, String payload) {
        if (signature == null) {
            throw new PaymentException(ErrorCode.PAY_006);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = Base64.getEncoder().encodeToString(
                    mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
            if (!computed.equals(signature)) {
                throw new PaymentException(ErrorCode.PAY_006);
            }
        } catch (PaymentException e) {
            throw e;
        } catch (Exception e) {
            throw new PaymentException(ErrorCode.PAY_006);
        }
    }
}
