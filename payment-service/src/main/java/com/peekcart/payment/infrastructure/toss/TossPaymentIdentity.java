package com.peekcart.payment.infrastructure.toss;

import com.fasterxml.jackson.databind.JsonNode;
import com.peekcart.payment.domain.model.PaymentApproval;

/** PG 상태를 판단하기 전에 승인 원장과 같은 결제인지 확인한다 (ADR-0042). */
final class TossPaymentIdentity {
    private TossPaymentIdentity() { }

    static boolean matches(PaymentApproval approval, JsonNode body) {
        return body != null && body.isObject()
                && body.path("paymentKey").isTextual()
                && approval.getPaymentKey().equals(body.path("paymentKey").textValue())
                && body.path("orderId").isTextual()
                && approval.effectivePgOrderId().equals(body.path("orderId").textValue())
                && body.path("totalAmount").isIntegralNumber()
                && body.path("totalAmount").canConvertToLong()
                && approval.getAmount() == body.path("totalAmount").longValue();
    }
}
