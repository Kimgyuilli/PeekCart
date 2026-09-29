package com.peekcart.payment.domain.model;

/**
 * 결제 상태 enum. 허용된 상태 전이 규칙을 직접 보유한다.
 */
public enum PaymentStatus {

    PENDING {
        @Override
        public boolean canTransitionTo(PaymentStatus target) {
            return target == APPROVED || target == FAILED;
        }
    },
    /** 승인 완료. 환불(보상)로만 벗어난다(ADR-0018 D2). */
    APPROVED {
        @Override
        public boolean canTransitionTo(PaymentStatus target) {
            return target == REFUNDED;
        }
    },
    FAILED {
        @Override
        public boolean canTransitionTo(PaymentStatus target) {
            return false;
        }
    },
    /** 결제 시작 전 주문이 취소되어 종료된 상태 (order.cancelled 소비, 로컬 전용·이벤트 미발행). */
    CANCELLED {
        @Override
        public boolean canTransitionTo(PaymentStatus target) {
            return false;
        }
    },

    /**
     * 승인된 결제가 환불로 종결된 상태 (ADR-0018 D2).
     * 환불 진행 상태(REQUESTED/CLAIMED/UNRESOLVED)는 payment_refunds 원장이 소유하며,
     * payments 에는 <b>확정된 종결</b>만 반영한다.
     */
    REFUNDED {
        @Override
        public boolean canTransitionTo(PaymentStatus target) {
            return false;
        }
    };

    public abstract boolean canTransitionTo(PaymentStatus target);
}
