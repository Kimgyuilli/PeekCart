package com.peekcart.payment.application;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * 결제 승인 정책 (계획 ④-a GW-2 #1 · D-020/ADR-0023). 동작 정책이므로 base {@code application.yml}
 * 소유(ADR-0007).
 *
 * <p>{@code leaseApprovalMargin} 은 PG 승인에 허용하는 최대 소요 시간이다. 남은 예약 lease 가 이보다
 * 짧으면 승인을 <b>시작하지 않는다</b> — 승인 도중 lease 가 만료되면 Order 의 만료 취소가 재고를
 * 복구·재판매한 뒤 과금이 성립할 수 있기 때문이다.
 *
 * <p><b>한계</b>: 이는 경합 창을 마진 이내로 줄이는 조치이지 fence 가 아니다. PG 호출이 마진을 초과하면
 * 창은 다시 열린다. 근본 해결(예약을 승인 전용 상태로 CAS 전이) 은 별도 ADR 대상이다(계획 §2.6 R-1).
 */
@ConfigurationProperties(prefix = "app.payment")
@Validated
@Getter
@Setter
public class PaymentApprovalProperties {

    /** PG 승인 최대 소요 예상 시간. 남은 lease 가 이보다 짧으면 PAY-010 으로 거부한다. */
    @NotNull
    private Duration leaseApprovalMargin;

    /** 승인 원장 reconciliation 정책 (ADR-0023 D5). */
    @Valid
    @NotNull
    private Approval approval = new Approval();

    @Getter
    @Setter
    public static class Approval {

        /**
         * stale claim 판정 기준. 이 시간을 넘긴 {@code CLAIMED} 는 reconciliation 이 회수한다.
         *
         * <p><b>환불의 {@code claim-lease} 와 달리 안전 불변식이 아니다.</b> 환불은 lease 를 짧게 잡으면
         * 살아있는 claim 이 회수돼 <b>같은 결제에 취소를 두 번 시도</b>하지만, 승인 reconciliation 은
         * 조회만 하고 재호출하지 않으므로(ADR-0023 D5) 조기 회수의 최악은 <b>불필요한 조회 1회</b>다.
         * 그래서 PG 타임아웃과의 관계를 부팅 시 강제하지 않는다 — 강제하려면 {@code app.refund.pg-timeout}
         * 을 여기에 복제해야 하고, 그 복제가 드리프트하면 검증 자체가 거짓이 된다.
         */
        @NotNull
        private Duration claimLease;

        /** reconciliation 1회 실행당 조회 건수(배치 크기). */
        @Min(1)
        private int batchSize;

        /** 1회 실행당 최대 배치 수 — 한 배치가 계속 미확정이어도 다음 배치가 굶지 않게 한다. */
        @Min(1)
        private int maxBatchesPerRun;

        /** reconciliation 실행 간격(ms). {@code @Scheduled} placeholder 와 같은 키를 소유한다. */
        @Min(1000)
        private long reconcileIntervalMs;

        /** ShedLock lockAtMostFor. 한 배치의 최악 실행 시간보다 길어야 다중 인스턴스 겹침이 없다. */
        @NotNull
        private Duration lockAtMostFor;

        /** 결과 불명 상태를 자동 확정 시도하는 상한. 초과 시 운영 알림 + 수동 종결 대상. */
        @NotNull
        private Duration unresolvedLimit;

        /**
         * 미해결 상한은 claim lease 보다 길어야 한다 — 짧으면 reconciliation 이 한 번도 회수해 보기
         * 전에 수동 종결 대상이 된다(자동 확정 기회를 스스로 없애는 설정).
         */
        @AssertTrue(message = "app.payment.approval.unresolved-limit 는 claim-lease 보다 길어야 합니다")
        public boolean isUnresolvedLimitLongerThanClaimLease() {
            if (unresolvedLimit == null || claimLease == null) {
                return true;   // @NotNull 이 먼저 보고하게 둔다
            }
            return unresolvedLimit.compareTo(claimLease) > 0;
        }
    }
}
