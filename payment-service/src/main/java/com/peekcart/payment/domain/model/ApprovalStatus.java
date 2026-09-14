package com.peekcart.payment.domain.model;

/**
 * 승인 원장 상태 (ADR-0023 D2). 허용된 상태 전이 규칙을 직접 보유한다.
 *
 * <p>환불 원장의 {@code REQUESTED} 에 대응하는 상태가 <b>없다</b> — 환불은 비동기 트리거라
 * "요청은 커밋됐고 실행자는 아직 안 집었다" 는 구간이 실재하지만, 승인은 사용자 HTTP 요청이
 * 곧 실행 시작이라 그 구간이 없다. 만들면 도달하지 않는 상태가 된다.
 *
 * <p>{@code UNRESOLVED} 는 <b>종결이 아니다</b> — reconciliation 이 PG 조회로 확정하거나,
 * 상한을 넘기면 수동 종결로 {@code FAILED} 에 도달한다.
 */
public enum ApprovalStatus {

    /** T1 커밋됨. PG 호출 중이거나, 호출 직전/직후에 죽었다. {@code claimed_at} 이 lease 기준이다. */
    CLAIMED {
        @Override
        public boolean canTransitionTo(ApprovalStatus target) {
            // CLAIMED → CLAIMED = lease 만료 claim 회수 후 재claim (reconciliation 이 수행)
            return target == SUCCEEDED || target == FAILED || target == UNRESOLVED || target == CLAIMED;
        }
    },

    /** 승인 확정 (종결). */
    SUCCEEDED {
        @Override
        public boolean canTransitionTo(ApprovalStatus target) {
            return false;
        }
    },

    /** 승인 실패 확정 (종결). */
    FAILED {
        @Override
        public boolean canTransitionTo(ApprovalStatus target) {
            return false;
        }
    },

    /**
     * 결과 불명 — 종결이 아니라 미해결. reconciliation 또는 수동 종결로만 벗어난다.
     *
     * <p>→ {@code CLAIMED} 전이가 필요한 이유는 환불 원장과 같다: 상태를 그대로 두고 조회하면
     * 진행 중인 건을 운영자가 수동 종결(UNRESOLVED 대상)할 수 있고, 그 뒤 조회가 과금을
     * 확인해도 이미 terminal 이라 반영되지 않는다.
     */
    UNRESOLVED {
        @Override
        public boolean canTransitionTo(ApprovalStatus target) {
            return target == SUCCEEDED || target == FAILED || target == CLAIMED;
        }
    };

    public abstract boolean canTransitionTo(ApprovalStatus target);

    /** 더 이상 처리가 필요 없는 종결 상태인가. */
    public boolean isTerminal() {
        return this == SUCCEEDED || this == FAILED;
    }
}
