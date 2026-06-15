package com.peekcart.notification.domain.model;

/**
 * 알림 유형. notifications 테이블의 type 컬럼에 대응한다.
 */
public enum NotificationType {
    ORDER_CREATED,
    PAYMENT_COMPLETED,
    PAYMENT_FAILED,
    ORDER_CANCELLED
}
