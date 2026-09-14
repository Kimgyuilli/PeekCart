package com.peekcart.payment.infrastructure.scheduler;

import com.peekcart.global.port.SlackPort;
import com.peekcart.payment.application.PaymentApprovalProperties;
import com.peekcart.payment.application.PaymentApprovalService;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.payment.infrastructure.toss.ApprovalExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 승인 결과 확정 잡 (ADR-0023 D5).
 *
 * <p>T1/T2 분해는 <b>커밋 경계</b>를 고쳤을 뿐, 외부 호출 경계의 crash 자체를 없애지 못한다.
 * 남는 관측 상태:
 * <ul>
 *   <li>(a) T1 커밋 후 PG 호출 전 사망 → {@code CLAIMED} lease 만료</li>
 *   <li>(b) PG 성공 후 T2 커밋 전 사망 → 동일. 과금은 성립했고 로컬은 {@code PENDING} 이다</li>
 *   <li>(c) 타임아웃·5xx → {@code UNRESOLVED}</li>
 * </ul>
 * 셋 다 <b>PG 조회로 진실을 확정</b>하는 것으로 수렴한다. (b) 가 D-020 이 지목한 바로 그 상태이며,
 * T1 이 실제 {@code paymentKey} 를 커밋해 둔 덕에 여기서 물어볼 수 있다(ADR-0023 C2).
 *
 * <p><b>승인을 재호출하지 않는다</b> — 재호출은 없던 과금을 새로 만드는 행위다(D5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalReconciliationScheduler {

    private final PaymentApprovalService approvalService;
    private final ApprovalExecutor approvalExecutor;
    private final PaymentApprovalProperties properties;
    private final SlackPort slackPort;

    @Scheduled(fixedDelayString = "${app.payment.approval.reconcile-interval-ms}")
    @SchedulerLock(name = "approvalReconcileJob",
            lockAtMostFor = "${app.payment.approval.lock-at-most-for}", lockAtLeastFor = "PT30S")
    public void reconcile() {
        for (int batch = 0; batch < properties.getApproval().getMaxBatchesPerRun(); batch++) {
            List<Long> candidates = approvalService.findReconcileCandidates();
            if (candidates.isEmpty()) {
                return;
            }
            processBatch(candidates);
        }
    }

    private void processBatch(List<Long> candidates) {
        for (Long orderId : candidates) {
            try {
                reconcileOne(orderId);
            } catch (Exception e) {
                // 한 건의 실패가 배치를 멈추지 않게 한다. 원장은 열린 채 남아 다음 순회 대상이 된다.
                log.error("승인 확정 실패 — orderId={}", orderId, e);
            }
        }
    }

    private void reconcileOne(Long orderId) {
        // per-row claim: 한 인스턴스만 외부 조회한다(ShedLock 만료 시 배치 겹침 방지).
        Optional<PaymentApproval> claimed = approvalService.claimForReconcile(orderId);
        if (claimed.isEmpty()) {
            return;
        }
        PaymentApproval approval = claimed.get();

        ApprovalExecutor.CallResult result = approvalExecutor.verify(approval);
        approvalService.finalizeApproval(orderId, approval.getGeneration(), result.outcome(), result.attempts());

        approvalService.find(orderId).ifPresent(this::escalateIfOverLimit);
    }

    /** 자동 확정 상한(정책값)을 넘긴 미해결은 운영 알림 + 수동 종결 대상으로 남긴다. */
    private void escalateIfOverLimit(PaymentApproval approval) {
        if (approval.isTerminal()) {
            return;
        }
        LocalDateTime limit = approval.getRequestedAt().plus(properties.getApproval().getUnresolvedLimit());
        if (LocalDateTime.now().isBefore(limit)) {
            return;
        }
        log.error("승인 미해결 상한 초과 — 수동 종결 필요, orderId={}, requestedAt={}",
                approval.getOrderId(), approval.getRequestedAt());
        slackPort.send("[승인 미해결] orderId=" + approval.getOrderId()
                + " — 자동 확정 상한 초과. PG 조회 후 수동 종결 필요(과금 성립 여부 확인).");
    }
}
