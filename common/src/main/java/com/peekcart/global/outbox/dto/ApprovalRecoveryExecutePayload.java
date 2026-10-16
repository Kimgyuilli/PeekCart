package com.peekcart.global.outbox.dto;

/** D-084 복구 프로토콜. recoveryId와 generation으로 오래된 회신을 거절한다. */
public record ApprovalRecoveryExecutePayload(String recoveryId, Long orderId, long generation, long reservationVersion) {
}
