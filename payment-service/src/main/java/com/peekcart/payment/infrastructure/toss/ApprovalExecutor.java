package com.peekcart.payment.infrastructure.toss;

import com.peekcart.payment.application.ApprovalOutcome;
import com.peekcart.payment.domain.model.PaymentApproval;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.Set;

/**
 * PG 승인 호출 + 조회 확정 실행기 (ADR-0023 D3/D4/D5).
 *
 * <p>요청 경로와 reconciliation 이 <b>같은 멱등키</b>를 쓰도록 한 곳에 둔다 — 재호출 경로가 다른
 * 멱등키를 쓰면 PG 측 중복 방어가 무력해진다. 트랜잭션을 열지 않으며, 호출자가 claim(T1)과
 * 확정(T2) 사이에서 부른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalExecutor {

    /** PG 가 "과금이 성립했다" 고 말하는 유일한 상태. */
    private static final String DONE = "DONE";

    /**
     * 과금이 성립하지 않았거나 이미 되돌려진 것이 <b>확정된</b> 상태들.
     * 여기에 없는 상태(READY·IN_PROGRESS·WAITING_FOR_DEPOSIT 등)는 <b>미확정</b>이며 닫지 않는다.
     */
    private static final Set<String> TERMINAL_NEGATIVE =
            Set.of("CANCELED", "PARTIAL_CANCELED", "ABORTED", "EXPIRED");

    private final TossPaymentClient tossPaymentClient;

    /**
     * 신규 승인 실행 — <b>1회만 호출한다</b> (ADR-0023 D4).
     *
     * <p>환불 dispatcher 와 달리 재시도 루프가 없다. 이 호출은 사용자가 기다리는 동기 요청 안에서
     * 일어나므로, 재시도하면 응답이 시도 수에 비례해 늘어지고 그동안 예약 lease 가 만료된다
     * ({@code leaseApprovalMargin} 의 전제가 깨진다). 일시 실패는 {@code UNRESOLVED} 로 넘겨
     * <b>재시도의 소유자를 배경 잡으로 옮긴다</b>.
     */
    public CallResult execute(PaymentApproval approval) {
        TossOutcome outcome = tossPaymentClient.confirm(
                approval.getPaymentKey(),
                approval.getOrderId().toString(),
                approval.getAmount(),
                idempotencyKey(approval.getOrderId()));

        return switch (outcome.kind()) {
            case SUCCEEDED -> new CallResult(toSucceeded(outcome.rawResponse()), 1);
            case PERMANENT_FAILURE -> new CallResult(
                    ApprovalOutcome.failed(outcome.code(), outcome.rawResponse()), 1);
            // 이미 승인됨 = 이전 호출이 성공하고 응답만 유실된 경우. 조회로 진실을 가른다(D3).
            case ALREADY_PROCESSED -> new CallResult(verifyByQuery(approval), 1);
            // 타임아웃·5xx·429 — 과금이 성립했는지 알 수 없다. 여기서 실패로 닫지 않는다.
            default -> new CallResult(ApprovalOutcome.unresolved(outcome.rawResponse()), 1);
        };
    }

    /**
     * 확정 실행 (reconciliation) — <b>조회만</b> 한다 (ADR-0023 D5).
     *
     * <p>환불의 {@code verifyThenExecute} 는 "취소된 적 없음" 이 확정되면 같은 멱등키로 재호출하지만,
     * 승인은 재호출하지 않는다. 재호출은 <b>없던 과금을 새로 만드는 행위</b>이고, 그 시점엔 사용자
     * 세션도 예약 lease 도 이미 없다. 여기서 하는 일은 과금 여부의 확정뿐이다.
     */
    public CallResult verify(PaymentApproval approval) {
        return new CallResult(verifyByQuery(approval), 0);
    }

    /** 주문 단위 안정 멱등키 — 최초 호출·사용자 재시도·reconciliation 에서 모두 동일하다. */
    public String idempotencyKey(Long orderId) {
        return "approve-" + orderId;
    }

    private ApprovalOutcome verifyByQuery(PaymentApproval approval) {
        Optional<TossPaymentSnapshot> snapshot = tossPaymentClient.find(approval.getPaymentKey());
        if (snapshot.isEmpty()) {
            return ApprovalOutcome.unresolved("PG 조회 실패 — 진실 미확정");
        }
        TossPaymentSnapshot found = snapshot.get();
        String status = found.status();

        if (DONE.equals(status)) {
            return toSucceeded(found.rawResponse());
        }
        if (TERMINAL_NEGATIVE.contains(status)) {
            return ApprovalOutcome.failed("PG_" + status, found.rawResponse());
        }
        // READY·IN_PROGRESS·WAITING_FOR_DEPOSIT·미상 — 아직 확정되지 않았다. 닫으면 뒤늦게 성립한
        // 과금이 로컬 FAILED 와 영구히 어긋난다(ADR-0023 D5).
        log.info("PG 승인 상태 미확정 — 원장을 닫지 않는다, paymentKey={}, status={}",
                approval.getPaymentKey(), status);
        return ApprovalOutcome.unresolved("PG 상태 미확정: " + status);
    }

    /**
     * 승인 성공 응답을 원장 결론으로 바꾼다.
     * {@code approvedAt} 파싱이 실패하면 <b>현재 시각으로 대체</b>한다 — 값 하나 때문에 성립한
     * 과금을 미확정으로 되돌리면, 진실이 확정됐는데도 원장이 열린 채 남는다.
     */
    private ApprovalOutcome toSucceeded(String rawResponse) {
        TossConfirmResponse parsed = tossPaymentClient.parseConfirmed(rawResponse);
        return ApprovalOutcome.succeeded(parsed.method(), parseApprovedAt(parsed.approvedAt()), rawResponse);
    }

    private LocalDateTime parseApprovedAt(String approvedAt) {
        if (approvedAt == null || approvedAt.isBlank()) {
            return LocalDateTime.now();
        }
        try {
            return OffsetDateTime.parse(approvedAt).toLocalDateTime();
        } catch (Exception e) {
            log.warn("PG 승인 시각 파싱 실패 — 현재 시각으로 대체, approvedAt={}", approvedAt);
            return LocalDateTime.now();
        }
    }

    public record CallResult(ApprovalOutcome outcome, int attempts) {
    }
}
