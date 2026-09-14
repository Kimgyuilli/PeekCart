package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.repository.PaymentApprovalRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 미확정 승인 backlog 관측 (ADR-0023 Consequences).
 *
 * <p>"미결로 남기지 않는다"는 계약은 <b>미해결 건수와 그 나이가 보여야</b> 검증 가능하다.
 * Slack 은 배포 구성상 no-op 일 수 있으므로 관측성의 대체물이 아니다.
 *
 * <p>{@code CLAIMED} 도 backlog 다 — 정상 승인은 같은 요청 안에서 종결되므로, 여기 남아 있다는
 * 것은 T2 에 도달하지 못한 건(과금 고아 후보)이라는 뜻이다.
 */
@Component
public class ApprovalBacklogMetrics {

    private static final ApprovalStatus[] BACKLOG_STATUSES = {
            ApprovalStatus.CLAIMED, ApprovalStatus.UNRESOLVED
    };

    public ApprovalBacklogMetrics(MeterRegistry meterRegistry, PaymentApprovalRepository approvalRepository) {
        for (ApprovalStatus status : BACKLOG_STATUSES) {
            String tag = status.name().toLowerCase();

            Gauge.builder("payment.approval.backlog", approvalRepository, repo -> repo.countByStatus(status))
                    .description("미확정 승인 원장 건수 (scrape 시점 집계)")
                    .tag("status", tag)
                    .register(meterRegistry);

            Gauge.builder("payment.approval.oldest.age", approvalRepository, repo -> oldestAgeSeconds(repo, status))
                    .description("해당 상태에서 가장 오래된 승인 요청의 경과 시간(초)")
                    .baseUnit("seconds")
                    .tag("status", tag)
                    .register(meterRegistry);
        }
    }

    private static double oldestAgeSeconds(PaymentApprovalRepository repository, ApprovalStatus status) {
        return repository.findOldestRequestedAt(status)
                .map(requestedAt -> (double) Duration.between(requestedAt, LocalDateTime.now()).toSeconds())
                .orElse(0.0);
    }
}
