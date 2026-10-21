package com.peekcart.payment.application.dto;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.domain.exception.PaymentException;

import java.util.Set;

/** A validated notification shape, never evidence of a payment outcome (ADR-0041). */
public record PaymentStatusHint(String eventType, String paymentKey, String reportedStatus) {
    public static final String EVENT_TYPE = "PAYMENT_STATUS_CHANGED";
    private static final Set<String> STATUSES = Set.of("READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT",
            "DONE", "CANCELED", "PARTIAL_CANCELED", "ABORTED", "EXPIRED");

    public PaymentStatusHint {
        if (!EVENT_TYPE.equals(eventType) || paymentKey == null || paymentKey.isBlank()
                || paymentKey.length() > 200 || reportedStatus == null || !STATUSES.contains(reportedStatus)) {
            throw new PaymentException(ErrorCode.SYS_003);
        }
    }

    /** Only allowlisted values enter the audit, not the untrusted full Payment/customer object. */
    public String auditPayload() {
        return "{\"eventType\":\"" + EVENT_TYPE + "\",\"reportedStatus\":\"" + reportedStatus + "\"}";
    }
}
