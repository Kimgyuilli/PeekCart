package com.peekcart.payment.application;

import com.peekcart.payment.application.dto.PaymentStatusHint;
import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.domain.repository.WebhookLogRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * General payment webhooks are untrusted, bounded reconciliation hints (ADR-0041).
 * Only server PG queries can determine payment outcomes. This transaction never calls PG.
 */
@Service
@Transactional
@RequiredArgsConstructor
public class WebhookService {
    private final WebhookLogRepository webhookLogRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentApprovalRepository approvalRepository;
    private final PaymentApprovalService approvalService;

    public void processWebhook(PaymentStatusHint hint) {
        // The existing unique payment_key index bounds unknown-key lookup; unknown hints write nothing.
        var payment = paymentRepository.findByPaymentKey(hint.paymentKey());
        if (payment.isEmpty()) return;
        var approval = approvalRepository.findByOrderIdForUpdate(payment.get().getOrderId());
        if (approval.isEmpty() || approval.get().getStatus() != ApprovalStatus.UNRESOLVED
                || !hint.paymentKey().equals(approval.get().getPaymentKey())) return;

        // Never use an attacker-controlled transmission ID or status as a source of new work.
        // One hint per payment lifetime; normal polling continues after this acceleration.
        String dedupKey = "payment-status-hint:" + hint.paymentKey();
        if (webhookLogRepository.insertIfAbsent(hint.paymentKey(), hint.eventType(), dedupKey,
                hint.auditPayload(), LocalDateTime.now()) == 1) {
            approvalService.nudge(hint.paymentKey());
        }
    }
}
