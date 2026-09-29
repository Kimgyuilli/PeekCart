package com.peekcart.order.domain.model;

/**
 * 보상 원장 처리 상태. 전이는 {@code payment.refunded} 회신이 수행한다 (ADR-0018 D4).
 *
 * <p>종착이 둘인 이유: {@code RESOLVED} 는 "<b>환불 완료</b>"를 뜻한다. 환불이 실패했는데 원장을
 * 해결됨으로 닫으면 그 원장은 거짓말을 한다 — 실패는 "닫혔지만 해결되지 않음"으로 남아야
 * 운영 대상이 된다.
 */
public enum CompensationStatus {

    /** 감지됨. 아직 환불 결과 회신 전. */
    OPEN,

    /** 환불 성공으로 해소됨. */
    RESOLVED,

    /** 환불이 영구 실패했다 — 자동 재시도 없음, 운영이 처리한다. */
    REFUND_FAILED
}
