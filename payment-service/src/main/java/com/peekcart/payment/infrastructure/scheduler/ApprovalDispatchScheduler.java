package com.peekcart.payment.infrastructure.scheduler;

import com.peekcart.payment.application.PaymentApprovalService;
import com.peekcart.payment.application.PaymentApprovalProperties;
import com.peekcart.payment.infrastructure.toss.ApprovalExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 재고 보호가 커밋된 READY만 PG 호출한다. CLAIMED 이후 실패는 조회 전용 reconciler 소관. */
@Slf4j
@Component
@RequiredArgsConstructor
public class ApprovalDispatchScheduler {
    private final PaymentApprovalService approvalService;
    private final ApprovalExecutor approvalExecutor;
    private final PaymentApprovalProperties properties;

    @Scheduled(fixedDelayString = "${app.payment.approval.dispatch-interval-ms}")
    @SchedulerLock(name = "approvalDispatchJob", lockAtMostFor = "${app.payment.approval.lock-at-most-for}")
    public void dispatch() {
        for (int batch = 0; batch < properties.getApproval().getMaxBatchesPerRun(); batch++) {
            var candidates = approvalService.findDispatchCandidates();
            if (candidates.isEmpty()) {
                return;
            }
            for (Long orderId : candidates) {
                try {
                    approvalService.claimForDispatch(orderId).ifPresent(approval -> {
                        var result = approvalExecutor.execute(approval);
                        approvalService.finalizeApproval(orderId, approval.getGeneration(),
                                result.outcome(), result.attempts());
                    });
                } catch (Exception failure) {
                    log.error("승인 dispatch 실패 — 원장 상태에 따라 다음 순회/조회로 복구, orderId={}", orderId, failure);
                }
            }
        }
    }
}
