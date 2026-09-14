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
 *
 * <p>웹훅은 <b>진실의 출처가 아니라 신호다</b> (ADR-0023 D7). payload 를 믿고 상태를 바꾸면
 * 서명이 유효한 재전송/순서 역전이 로컬 상태를 되돌릴 수 있다. 그래서 여기서는 미확정 승인
 * 원장의 lease 만 비워 <b>다음 reconcile 순회의 최우선</b>으로 만들고, 확정은 PG 조회에 맡긴다.
 * 웹훅 트랜잭션 안에서 PG 를 호출하지 않는다는 규약(ADR-0018 D3)도 그대로 적용된다.
 */
@Service
@Transactional
public class WebhookService {

    private final WebhookLogRepository webhookLogRepository;
    private final PaymentApprovalService approvalService;
    private final String webhookSecret;

    public WebhookService(WebhookLogRepository webhookLogRepository,
                          PaymentApprovalService approvalService,
                          @Value("${toss.payments.webhook-secret}") String webhookSecret) {
        this.webhookLogRepository = webhookLogRepository;
        this.approvalService = approvalService;
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

        // 미확정 승인이 있으면 순회 우선순위만 올린다(ADR-0023 D7). paymentKey 가 비면 nudge 대상을
        // 특정할 수 없으므로 로그만 남긴 셈이 된다 — 웹훅 적재 자체는 성공으로 둔다.
        if (paymentKey != null && !paymentKey.isBlank()) {
            approvalService.nudge(paymentKey);
        }
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
