package com.peekcart.support.fixture;

import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.model.Payment;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

/**
 * Payment 도메인 테스트 픽스처 팩토리.
 */
public class PaymentFixture {

    public static final Long DEFAULT_PAYMENT_ID = 1L;
    public static final Long DEFAULT_ORDER_ID = 1L;
    public static final Long DEFAULT_USER_ID = 1L;
    public static final long DEFAULT_AMOUNT = 100_000L;
    public static final String DEFAULT_PAYMENT_KEY = "toss-payment-key-123";
    public static final String DEFAULT_METHOD = "카드";
    public static final LocalDateTime DEFAULT_APPROVED_AT = LocalDateTime.of(2026, 3, 25, 14, 0);
    public static final LocalDateTime DEFAULT_CREATED_AT = LocalDateTime.of(2026, 3, 25, 13, 50);

    private PaymentFixture() {}

    // ── Domain 객체 ──

    public static Payment pendingPayment() {
        return Payment.create(DEFAULT_ORDER_ID, DEFAULT_USER_ID, DEFAULT_AMOUNT);
    }

    public static Payment pendingPaymentWithId() {
        Payment payment = pendingPayment();
        ReflectionTestUtils.setField(payment, "id", DEFAULT_PAYMENT_ID);
        return payment;
    }

    /** 재고 예약이 확정되어 결제 진행 가능한(reserve→pay 게이트 통과) PENDING 결제. */
    public static Payment readyPaymentWithId() {
        Payment payment = pendingPaymentWithId();
        payment.markReadyForPayment();
        return payment;
    }

    public static Payment approvedPayment() {
        Payment payment = readyPaymentWithId();
        payment.assignPaymentKey(DEFAULT_PAYMENT_KEY);
        payment.approve(DEFAULT_METHOD, DEFAULT_APPROVED_AT);
        return payment;
    }

    public static Payment failedPayment() {
        Payment payment = pendingPaymentWithId();
        payment.fail();
        return payment;
    }

    // ── Application DTO ──

    public static ConfirmPaymentCommand confirmPaymentCommand() {
        return new ConfirmPaymentCommand(DEFAULT_PAYMENT_KEY, DEFAULT_ORDER_ID, DEFAULT_AMOUNT);
    }

    public static PaymentDetailDto approvedPaymentDetailDto() {
        return new PaymentDetailDto(
                DEFAULT_PAYMENT_ID, DEFAULT_ORDER_ID, DEFAULT_PAYMENT_KEY,
                DEFAULT_AMOUNT, "APPROVED", DEFAULT_METHOD,
                DEFAULT_APPROVED_AT, DEFAULT_CREATED_AT
        );
    }

    public static PaymentDetailDto failedPaymentDetailDto() {
        return new PaymentDetailDto(
                DEFAULT_PAYMENT_ID, DEFAULT_ORDER_ID, DEFAULT_PAYMENT_KEY,
                DEFAULT_AMOUNT, "FAILED", null,
                null, DEFAULT_CREATED_AT
        );
    }
}
