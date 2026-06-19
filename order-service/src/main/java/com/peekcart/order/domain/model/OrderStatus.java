package com.peekcart.order.domain.model;

import java.util.Set;

/**
 * 주문 상태 enum. 허용된 상태 전이 규칙을 직접 보유한다.
 */
public enum OrderStatus {

    PENDING {
        @Override
        public boolean canTransitionTo(OrderStatus target) {
            return target == PAYMENT_REQUESTED || target == CANCELLED;
        }
    },
    PAYMENT_REQUESTED {
        @Override
        public boolean canTransitionTo(OrderStatus target) {
            return target == PAYMENT_COMPLETED || target == PAYMENT_FAILED || target == CANCELLED;
        }
    },
    PAYMENT_COMPLETED {
        @Override
        public boolean canTransitionTo(OrderStatus target) {
            return target == PREPARING;
        }
    },
    PAYMENT_FAILED {
        @Override
        public boolean canTransitionTo(OrderStatus target) {
            return target == CANCELLED;
        }
    },
    PREPARING {
        @Override
        public boolean canTransitionTo(OrderStatus target) {
            return target == SHIPPED;
        }
    },
    SHIPPED {
        @Override
        public boolean canTransitionTo(OrderStatus target) {
            return target == DELIVERED;
        }
    },
    DELIVERED {
        @Override
        public boolean canTransitionTo(OrderStatus target) {
            return false;
        }
    },
    CANCELLED {
        @Override
        public boolean canTransitionTo(OrderStatus target) {
            return false;
        }
    };

    public abstract boolean canTransitionTo(OrderStatus target);
}
