package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.global.outbox.dto.RefundResult;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.model.PaymentRefund;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.domain.model.RefundStatus;
import com.peekcart.payment.domain.repository.PaymentRefundRepository;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.outbox.PaymentOutboxEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 환불 원장의 트랜잭션 경계를 소유하는 서비스 (ADR-0018 D3).
 *
 * <p><b>여기서 PG 를 호출하지 않는다.</b> 스케줄러가 claim(T1) → PG 호출(트랜잭션 밖) →
 * 확정(T2) 순으로 부르며, 각 단계가 독립 트랜잭션이어야 crash matrix 의 관측 상태가 성립한다.
 * 하나의 트랜잭션으로 묶으면 PG 호출 전 사망 시 CLAIMED 가 롤백돼 "claim 됐는데 호출 전 죽은
 * 상태"가 존재하지 않게 되고, PG 성공 후 롤백 시에는 fence 행 자체가 사라져 fence 가 무효가 된다.
 *
 * <p>확정은 <b>claim 당시의 generation</b> 을 함께 받는다 — lease 가 만료돼 소유권이 넘어간 뒤
 * 옛 worker 가 뒤늦게 확정하려 하면 no-op 이 된다(fencing token).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(RefundProperties.class)
public class PaymentRefundService {

    private final PaymentRefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentOutboxEventPublisher outboxEventPublisher;
    private final RefundProperties properties;
    private final MeterRegistry meterRegistry;

    /**
     * 환불 요청을 fence 와 함께 기록한다 (진입점 공통, ADR-0018 D3).
     *
     * <p>이미 원장이 있으면 <b>정상 no-op</b> 이다 — 예외로 던지면 소비가 DLQ 로 가는데, 중복
     * 트리거는 오류가 아니라 계약이 예상한 정상 경로다(감지 3지점).
     *
     * @return true = 이번 호출이 fence 를 획득
     */
    @Transactional
    public boolean requestRefund(Payment payment, String reason) {
        // 종결 검사가 APPROVED 검사보다 먼저다 — 환불이 성공하면 payments 는 REFUNDED 로 옮겨가므로
        // 상태 가드를 먼저 두면 "이미 환불된 결제"가 조기 반환돼 회신 재발행 경로에 닿지 못한다.
        if (republishIfAlreadyResolved(payment.getOrderId())) {
            return false;
        }
        if (payment.getStatus() != PaymentStatus.APPROVED) {
            return false;
        }
        if (payment.getUserId() == null) {
            // 회신 payload 의 userId 는 Notification 계약상 필수다(ADR-0018 D1). null 인 채로 환불을
            // 진행하면 성공 후 알림이 깨지므로, 환불을 시작하지 않고 사람이 보게 만든다.
            log.error("환불 요청 차단 — payments.user_id 가 null(ADR-0018 D1 위반), orderId={}",
                    payment.getOrderId());
            throw new PaymentException(ErrorCode.PAY_011);
        }
        return insertRequest(payment, reason);
    }

    /** fence 삽입 + 계수 — 정상/고아 두 진입점이 <b>같은 경로</b>를 쓰게 한다. */
    private boolean insertRequest(Payment payment, String reason) {
        int inserted = refundRepository.insertRequestedIfAbsent(
                payment.getOrderId(), payment.getPaymentKey(), payment.getUserId(), payment.getAmount());
        if (inserted == 0) {
            // 진행 중인 환불에 도착한 중복 트리거 — 결과가 아직 없으므로 회신할 것도 없다.
            log.debug("환불 요청 중복 — 이미 원장 존재(no-op), orderId={}", payment.getOrderId());
            return false;
        }
        Counter.builder("payment.refund.requested")
                .description("환불 요청 수신(fence 획득분)")
                .tag("reason", reason)
                .register(meterRegistry)
                .increment();
        log.info("환불 요청 기록 — orderId={}, reason={}", payment.getOrderId(), reason);
        return true;
    }

    /**
     * 이미 <b>종결된</b> 환불에 뒤늦은 요청이 도착하면 회신을 <b>다시 발행</b>한다.
     *
     * <p>없으면 감지가 늦은 소비자의 원장이 영구 미결로 남는다 — cross-topic 순서가 보장되지 않으므로
     * (ADR-0018 D1) {@code payment.refunded} 가 {@code payment.completed} 보다 먼저 도착하는 순서가
     * 존재한다. 그때 Order 는 아직 원장이 없어 회신을 no-op 하고 {@code processed_events} 로 봉인하며,
     * 뒤늦게 만든 {@code OPEN} 원장의 요청은 여기서 fence 에 막혀 회신을 못 받는다.
     *
     * <p>재발행이 안전한 근거는 <b>소비자 3곳이 모두 종결 후 재전달을 no-op</b> 으로 처리한다는 것이다
     * (ADR-0018 D4). 요청은 감지 3지점에서만 오므로 발행이 무한히 늘지 않는다.
     *
     * @return true = 종결된 환불이라 회신을 재발행했다(요청은 여기서 끝난다)
     */
    private boolean republishIfAlreadyResolved(Long orderId) {
        PaymentRefund existing = refundRepository.findByOrderId(orderId).orElse(null);
        if (existing == null || !existing.isTerminal()) {
            return false;
        }
        RefundResult result = existing.getStatus() == RefundStatus.SUCCEEDED
                ? RefundResult.SUCCEEDED
                : RefundResult.FAILED;
        publishResult(existing, result);
        log.info("종결된 환불에 뒤늦은 요청 도착 — 회신 재발행(늦게 생긴 원장 종결용), orderId={}, result={}",
                orderId, result);
        return true;
    }

    /**
     * 고아 과금의 환불 요청 (ADR-0023 D6) — <b>{@code APPROVED} 게이트만 우회</b>한다.
     *
     * <p>{@link #requestRefund} 의 상태 게이트는 "요청은 결제 승인 이후에만 발행된다"(감지 3지점이
     * 모두 {@code payment.completed} 이후)는 전제 위에 서 있다. 승인 reconciliation 은 <b>네 번째
     * 감지 지점</b>이며, 그 전제를 만족하지 않는다 — PG 에는 과금이 성립했는데 {@code payments} 는
     * 이미 {@code CANCELLED}/{@code FAILED} 로 닫힌 상태를 발견한다. 게이트를 그대로 두면 실재하는
     * 과금이 환불 경로에 진입하지 못한다.
     *
     * <p>우회가 안전한 근거는 {@link #succeed} 가 이미 {@code payments} 상태를 확인하고
     * {@code APPROVED} 일 때만 {@code markRefunded()} 한다는 것이다 — 이 건들은 원장에만 종결되고
     * {@code payments} 를 건드리지 않는다. fence · 중복 no-op · 종결 재발행 · 회신 발행은 전부
     * 같은 경로를 쓴다.
     *
     * @return true = 이번 호출이 fence 를 획득
     */
    @Transactional
    public boolean requestRefundForOrphanedCharge(Payment payment, String reason) {
        if (republishIfAlreadyResolved(payment.getOrderId())) {
            return false;
        }
        return insertRequest(payment, reason);
    }

    /**
     * T1(dispatcher) — <b>신규 요청만</b> claim 한다. lease 만료 claim 은 "PG 를 불렀는지 모르는"
     * 상태라 조회가 선행돼야 하므로 여기서 잡지 않는다(ADR-0018 D3 a/b).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentRefund> claimRequested(Long orderId) {
        if (refundRepository.claimRequested(orderId, LocalDateTime.now()) == 0) {
            return Optional.empty();
        }
        return refundRepository.findByOrderId(orderId);
    }

    /** T1(reconciliation) — lease 만료 CLAIMED · UNRESOLVED 를 claim 한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<PaymentRefund> claimForReconcile(Long orderId) {
        LocalDateTime now = LocalDateTime.now();
        if (refundRepository.claimForReconcile(orderId, now.minus(properties.getClaimLease()), now) == 0) {
            return Optional.empty();
        }
        return refundRepository.findByOrderId(orderId);
    }

    /**
     * T2 — 확정한다. 원장 전이 · {@code payments} 종결 · 회신 Outbox 가 <b>같은 트랜잭션</b>이라
     * 부분 성립이 없다. {@code generation} 이 어긋나면(소유권 상실) 아무것도 하지 않는다.
     *
     * <p>{@code UNRESOLVED} 는 회신을 발행하지 않는다 — 결과가 확정되지 않았는데 소비자 원장을
     * 닫으면 그 원장이 거짓이 된다(ADR-0018 D4).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeOutcome(Long orderId, long generation, RefundOutcome outcome, int attempts) {
        PaymentRefund refund = refundRepository.findByOrderIdForUpdate(orderId).orElseThrow();
        if (refund.isTerminal()) {
            log.debug("이미 종결된 환불 — 확정 no-op, orderId={}", orderId);
            return;
        }
        if (!refund.ownsGeneration(generation)) {
            // lease 가 만료돼 다른 인스턴스가 소유권을 가져갔다. 늦은 확정이 최신 상태를 덮지 않게 한다.
            log.warn("소유권 상실 — 확정 무시, orderId={}, myGeneration={}, current={}",
                    orderId, generation, refund.getGeneration());
            return;
        }
        refund.recordAttempts(attempts);

        switch (outcome.kind()) {
            case SUCCEEDED -> succeed(refund, outcome.detail());
            case FAILED -> fail(refund, outcome.code(), outcome.detail());
            case UNRESOLVED -> unresolved(refund, outcome.detail());
        }
    }

    /**
     * 운영자 수동 종결 (ADR-0018 D2). <b>{@code UNRESOLVED} 이면서 자동 확정 상한을 넘긴 건만</b>
     * 허용한다 — 진행 중인 {@code CLAIMED} 를 닫으면 실제로 성공한 환불을 실패로 회신할 수 있다.
     * 외부 API 로 노출하지 않는다.
     *
     * @throws PaymentException 상태·상한 조건 미충족이면 {@code PAY-004}
     */
    @Transactional
    public void resolveManually(Long orderId, String resolvedBy, String reason) {
        PaymentRefund refund = refundRepository.findByOrderIdForUpdate(orderId).orElseThrow();
        if (refund.isTerminal()) {
            return;   // 중복 종결 no-op
        }
        if (refund.getStatus() != RefundStatus.UNRESOLVED) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        if (LocalDateTime.now().isBefore(refund.getRequestedAt().plus(properties.getUnresolvedLimit()))) {
            throw new PaymentException(ErrorCode.PAY_004);
        }
        refund.resolveManually(resolvedBy, reason);
        publishResult(refund, RefundResult.FAILED);
        log.warn("환불 수동 종결 — orderId={}, by={}, reason={}", orderId, resolvedBy, reason);
    }

    public List<Long> findRequested() {
        return refundRepository.findRequested(properties.getBatchSize());
    }

    public List<Long> findReconcileCandidates() {
        return refundRepository.findReconcileCandidates(
                LocalDateTime.now().minus(properties.getClaimLease()), properties.getBatchSize());
    }

    public Optional<PaymentRefund> find(Long orderId) {
        return refundRepository.findByOrderId(orderId);
    }

    private void succeed(PaymentRefund refund, String rawResponse) {
        refund.markSucceeded(rawResponse);
        paymentRepository.findByOrderId(refund.getOrderId()).ifPresent(payment -> {
            if (payment.getStatus() == PaymentStatus.APPROVED) {
                payment.markRefunded();
            }
        });
        publishResult(refund, RefundResult.SUCCEEDED);
        countResult("succeeded");
        log.info("환불 성공 확정 — orderId={}", refund.getOrderId());
    }

    private void fail(PaymentRefund refund, String failureCode, String detail) {
        refund.markFailed(failureCode, detail);
        publishResult(refund, RefundResult.FAILED);
        countResult("failed");
        log.error("환불 영구 실패 — orderId={}, code={}", refund.getOrderId(), failureCode);
    }

    private void unresolved(PaymentRefund refund, String detail) {
        if (refund.getStatus() == RefundStatus.UNRESOLVED) {
            // 재확정 시도가 또 결과 불명 — 상태는 유지하고 사유만 갱신한다.
            refund.recordLastError(detail);
            return;
        }
        refund.markUnresolved(detail);
        Counter.builder("payment.refund.retry.exhausted")
                .description("PG 재시도 소진으로 결과 불명 전이")
                .register(meterRegistry)
                .increment();
        log.error("환불 결과 불명 — reconciliation 대상, orderId={}", refund.getOrderId());
    }

    private void publishResult(PaymentRefund refund, RefundResult result) {
        outboxEventPublisher.publishPaymentRefunded(refund, result);
    }

    private void countResult(String result) {
        Counter.builder("payment.refund.result")
                .description("확정된 환불 결과")
                .tag("result", result)
                .register(meterRegistry)
                .increment();
    }
}
