package com.peekcart.support.fixture;

import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentApproval;
import org.springframework.beans.BeanUtils;
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
        payment.markReadyForPayment(null);
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

    /**
     * 승인 원장 (ADR-0023 D2). 실제 행 생성은 {@code INSERT IGNORE} 라 엔티티로 만들 수 없으므로,
     * 단위 테스트에서는 리플렉션으로 T1 직후 상태(CLAIMED · generation=1)를 재현한다.
     */
    public static PaymentApproval claimedApproval() {
        return approval(ApprovalStatus.CLAIMED, 1L, LocalDateTime.now());
    }

    public static PaymentApproval approval(ApprovalStatus status, long generation, LocalDateTime claimedAt) {
        // 기본 생성자가 protected 다 — 원장 행은 INSERT IGNORE 로만 만들어지기 때문이다.
        PaymentApproval approval = BeanUtils.instantiateClass(PaymentApproval.class);
        ReflectionTestUtils.setField(approval, "id", DEFAULT_PAYMENT_ID);
        ReflectionTestUtils.setField(approval, "orderId", DEFAULT_ORDER_ID);
        ReflectionTestUtils.setField(approval, "paymentKey", DEFAULT_PAYMENT_KEY);
        ReflectionTestUtils.setField(approval, "userId", DEFAULT_USER_ID);
        ReflectionTestUtils.setField(approval, "amount", DEFAULT_AMOUNT);
        ReflectionTestUtils.setField(approval, "status", status);
        ReflectionTestUtils.setField(approval, "attempts", 0);
        ReflectionTestUtils.setField(approval, "generation", generation);
        ReflectionTestUtils.setField(approval, "claimedAt", claimedAt);
        ReflectionTestUtils.setField(approval, "requestedAt", DEFAULT_CREATED_AT);
        return approval;
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

    /** 결과 불명(ADR-0023 D8) — 승인이 확정되지 않아 PENDING 으로 남은 응답. */
    public static PaymentDetailDto pendingPaymentDetailDto() {
        return new PaymentDetailDto(
                DEFAULT_PAYMENT_ID, DEFAULT_ORDER_ID, DEFAULT_PAYMENT_KEY,
                DEFAULT_AMOUNT, "PENDING", null,
                null, DEFAULT_CREATED_AT
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
