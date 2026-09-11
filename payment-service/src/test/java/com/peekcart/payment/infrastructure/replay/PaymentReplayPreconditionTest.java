package com.peekcart.payment.infrastructure.replay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.infrastructure.PaymentJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V-28d(payment 축) — {@code reserved} × {@link PaymentStatus} 5종 × {@code readyForPayment} 2종
 * 전 조합 (계획 리뷰 2R #3 · 3R #4).
 *
 * <p><b>order 판정을 재사용할 수 없다</b> — DB-per-service 라 payment 는 Order 상태를 볼 수 없다.
 * 정책 키에서 소유자 축을 빼면 이 관통이 성립하지 않는다.
 */
class PaymentReplayPreconditionTest {

    private static final String TOPIC = "stock.reservation.result";

    private final PaymentJpaRepository paymentJpaRepository = mock(PaymentJpaRepository.class);
    private final PaymentReplayPrecondition precondition =
            new PaymentReplayPrecondition(paymentJpaRepository, new ObjectMapper());

    private static String payload(boolean reserved) {
        return """
                {"eventId":"e-1","eventType":"stock.reservation.result",
                 "payload":{"orderId":1,"reserved":%s}}""".formatted(reserved);
    }

    private void givenPayment(PaymentStatus status, boolean readyForPayment) {
        Payment payment = mock(Payment.class);
        when(payment.getStatus()).thenReturn(status);
        when(payment.isReadyForPayment()).thenReturn(readyForPayment);
        when(paymentJpaRepository.findByOrderIdForUpdate(anyLong())).thenReturn(Optional.of(payment));
    }

    @ParameterizedTest(name = "reserved=false · {0}/ready={1} → allow")
    @CsvSource({
            "PENDING,true", "PENDING,false", "APPROVED,true", "APPROVED,false",
            "FAILED,true", "FAILED,false", "CANCELLED,true", "CANCELLED,false",
            "REFUNDED,true", "REFUNDED,false"
    })
    @DisplayName("reserved=false 는 전 조합 allow — 소비 코드가 즉시 return 한다")
    void reservedFalseIsAlwaysAllowed(PaymentStatus status, boolean ready) {
        givenPayment(status, ready);

        assertThat(precondition.reject(TOPIC, payload(false))).isNull();
    }

    @Test
    @DisplayName("reserved=true · PENDING · ready=false → allow (유일한 allow 조합)")
    void reservedTrueOnPendingNotReady() {
        givenPayment(PaymentStatus.PENDING, false);

        assertThat(precondition.reject(TOPIC, payload(true))).isNull();
    }

    @Test
    @DisplayName("reserved=true · PENDING 이나 이미 ready → deny (재적용이 lease 만료 판정을 되돌린다)")
    void reservedTrueOnAlreadyReady() {
        givenPayment(PaymentStatus.PENDING, true);

        assertThat(precondition.reject(TOPIC, payload(true))).contains("이미 결제 준비가 완료");
    }

    @ParameterizedTest(name = "reserved=true · {0} → deny")
    @EnumSource(value = PaymentStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "PENDING")
    @DisplayName("reserved=true 는 PENDING 이 아닌 전 상태에서 deny — markReadyForPayment 에 상태 가드가 없다")
    void reservedTrueOnNonPending(PaymentStatus status) {
        givenPayment(status, false);

        assertThat(precondition.reject(TOPIC, payload(true)))
                .as("%s 에서 lease 를 덮으면 안 된다", status)
                .contains("이미 진행/종결된 상태");
    }

    @Test
    @DisplayName("결제 건 부재 → deny (fail-closed)")
    void paymentMissing() {
        when(paymentJpaRepository.findByOrderIdForUpdate(anyLong())).thenReturn(Optional.empty());

        assertThat(precondition.reject(TOPIC, payload(true))).contains("결제 건이 없다");
    }

    @Test
    @DisplayName("필드 부재·파싱 실패·대상 아닌 토픽 → deny")
    void failClosedInputs() {
        assertThat(precondition.reject(TOPIC, "{\"payload\":{}}")).contains("orderId/reserved");
        // 소수·지수·범위 초과 — canConvertToLong() 만 보면 통과해 다른 aggregate 를 조회한다(1R #2).
        assertThat(precondition.reject(TOPIC, "{\"payload\":{\"orderId\":1.5,\"reserved\":true}}"))
                .contains("orderId/reserved");
        assertThat(precondition.reject(TOPIC, "{\"payload\":{\"orderId\":1e2,\"reserved\":true}}"))
                .contains("orderId/reserved");
        assertThat(precondition.reject(TOPIC, "{\"payload\":{\"orderId\":99999999999999999999,\"reserved\":true}}"))
                .contains("orderId/reserved");
        assertThat(precondition.reject(TOPIC, "{not json")).contains("파싱하지 못했다");
        assertThat(precondition.reject("order.created", payload(true))).contains("판정 대상이 아닌 토픽");
    }
}
