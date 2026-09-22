package com.peekcart.order.infrastructure.metrics;

import com.peekcart.order.domain.model.CompensationReason;
import com.peekcart.order.domain.model.CompensationStatus;
import com.peekcart.order.domain.model.OrderCompensation;
import com.peekcart.order.domain.repository.OrderCompensationRepository;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.Search;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * order saga 메트릭 (계획 ④-d-1 §5 P2).
 *
 * <p>타임아웃 3종이 {@code reason} 으로 <b>구분되는지</b>를 본다 — 한 값으로 합치면 어느 잡이
 * 도는지 알 수 없고, 그러면 "취소가 늘었다" 까지만 알고 원인은 다시 로그를 읽어야 한다.
 *
 * <p>보상 backlog 는 Gauge 라 <b>원장 잔량을 그대로 따라가야</b> 한다 — 누적 Counter 로는
 * "지금 몇 건이 미해소인가" 에 답할 수 없어 alert 를 만들 데이터가 없다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("order saga 메트릭 통합 테스트")
class OrderSagaMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired OrderSagaMetrics sagaMetrics;
    @Autowired OrderCompensationRepository compensationRepository;
    @Autowired MeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("타임아웃 3종이 reason 으로 구분된다 — 한 잡만 돌면 나머지는 증가 0")
    void timeoutReasonsAreSeparate() {
        double expiredPaymentBefore = counter(OrderSagaMetrics.REASON_EXPIRED_PAYMENT);
        double unconfirmedBefore = counter(OrderSagaMetrics.REASON_UNCONFIRMED_RESERVATION);
        double leaseBefore = counter(OrderSagaMetrics.REASON_EXPIRED_LEASE);

        sagaMetrics.timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_PAYMENT, 2);

        assertThat(counter(OrderSagaMetrics.REASON_EXPIRED_PAYMENT)).isEqualTo(expiredPaymentBefore + 2);
        // 음성 대조 — 다른 사유는 그대로여야 한다
        assertThat(counter(OrderSagaMetrics.REASON_UNCONFIRMED_RESERVATION)).isEqualTo(unconfirmedBefore);
        assertThat(counter(OrderSagaMetrics.REASON_EXPIRED_LEASE)).isEqualTo(leaseBefore);
    }

    @Test
    @DisplayName("취소 0건이면 증가 0 — 스케줄러 실행당 1 이 아니다")
    void zeroCancelledDoesNotIncrement() {
        double before = counter(OrderSagaMetrics.REASON_EXPIRED_LEASE);

        sagaMetrics.timeoutCancelled(OrderSagaMetrics.REASON_EXPIRED_LEASE, 0);

        assertThat(counter(OrderSagaMetrics.REASON_EXPIRED_LEASE)).isEqualTo(before);
    }

    @Test
    @DisplayName("알 수 없는 사유는 조용히 삼키지 않고 예외 — 오타가 메트릭을 유실시키면 안 된다")
    void unknownReasonThrows() {
        assertThatThrownBy(() -> sagaMetrics.timeoutCancelled("typo_reason", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("보상 backlog Gauge 가 원장 잔량을 따라간다 — OPEN 2건 · REFUND_FAILED 1건")
    void compensationBacklogTracksLedger() {
        assertThat(gauge("saga.compensation.backlog", "status", "open")).isZero();

        compensationRepository.save(OrderCompensation.open(1L, CompensationReason.PAID_BUT_CANCELLED, "d"));
        compensationRepository.save(OrderCompensation.open(2L, CompensationReason.PAID_BUT_CANCELLED, "d"));

        assertThat(gauge("saga.compensation.backlog", "status", "open")).isEqualTo(2.0);
        assertThat(gauge("saga.compensation.backlog", "status", "refund_failed")).isZero();

        OrderCompensation failed =
                compensationRepository.save(OrderCompensation.open(3L, CompensationReason.PAID_BUT_CANCELLED, "d"));
        failed.failByRefund("PG_PERMANENT", LocalDateTime.now());
        compensationRepository.save(failed);

        assertThat(gauge("saga.compensation.backlog", "status", "open")).isEqualTo(2.0);
        assertThat(gauge("saga.compensation.backlog", "status", "refund_failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("종결하면 backlog 가 줄어든다 — 누적이 아니라 잔량이다")
    void backlogDecreasesOnResolution() {
        OrderCompensation open =
                compensationRepository.save(OrderCompensation.open(4L, CompensationReason.PAID_BUT_CANCELLED, "d"));
        assertThat(gauge("saga.compensation.backlog", "status", "open")).isEqualTo(1.0);

        open.resolveByRefund(LocalDateTime.now());
        compensationRepository.save(open);

        assertThat(gauge("saga.compensation.backlog", "status", "open")).isZero();
        assertThat(compensationRepository.countByStatus(CompensationStatus.RESOLVED)).isEqualTo(1L);
    }

    // ---------- helpers ----------

    private double counter(String reason) {
        var c = meterRegistry.find("saga.order.timeout.cancel").tag("reason", reason).counter();
        return c == null ? 0.0 : c.count();
    }

    private double gauge(String name, String tagKey, String tagValue) {
        Search search = meterRegistry.find(name).tag(tagKey, tagValue);
        var g = search.gauge();
        return g == null ? 0.0 : g.value();
    }
}
