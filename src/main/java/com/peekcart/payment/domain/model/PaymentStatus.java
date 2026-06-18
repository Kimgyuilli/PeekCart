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
    APPROVED {
        @Override
        public boolean canTransitionTo(PaymentStatus target) {
            return false;
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
    };

    public abstract boolean canTransitionTo(PaymentStatus target);
}
