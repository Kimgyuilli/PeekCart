package com.peekcart.payment.application;

import com.peekcart.payment.application.dto.PaymentStatusHint;
import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.domain.repository.WebhookLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {
    @Mock WebhookLogRepository logs;
    @Mock PaymentRepository payments;
    @Mock PaymentApprovalRepository approvals;
    @Mock PaymentApprovalService service;
    private WebhookService webhook;
    private final PaymentStatusHint hint = new PaymentStatusHint("PAYMENT_STATUS_CHANGED", "known-key", "DONE");

    @BeforeEach void setup() { webhook = new WebhookService(logs, payments, approvals, service); }

    @Test void unknownKeyCreatesNoWork() {
        when(payments.findByPaymentKey("known-key")).thenReturn(Optional.empty());
        webhook.processWebhook(hint);
        verifyNoInteractions(logs, approvals, service);
    }

    @Test void onlyInsertedServerFenceNudges() {
        eligible();
        when(logs.insertIfAbsent(eq("known-key"), eq("PAYMENT_STATUS_CHANGED"),
                eq("payment-status-hint:known-key"), eq(hint.auditPayload()), any())).thenReturn(1);
        webhook.processWebhook(hint);
        verify(service).nudge("known-key");
    }

    @Test void duplicateFenceDoesNotNudgeAgain() {
        eligible();
        webhook.processWebhook(hint);
        verifyNoInteractions(service);
    }

    private void eligible() {
        Payment payment = mock(Payment.class);
        PaymentApproval approval = mock(PaymentApproval.class);
        when(payment.getOrderId()).thenReturn(1L);
        when(payments.findByPaymentKey("known-key")).thenReturn(Optional.of(payment));
        when(approvals.findByOrderIdForUpdate(1L)).thenReturn(Optional.of(approval));
        when(approval.getStatus()).thenReturn(ApprovalStatus.UNRESOLVED);
        when(approval.getPaymentKey()).thenReturn("known-key");
    }
}
