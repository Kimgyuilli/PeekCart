package com.peekcart.global.outbox.dto;

/**
 * {@code order.cancelled} 취소 사유 (ADR-0012 D2 하위호환 필드 추가).
 *
 * <p>취소 진입점마다 사유를 명시적으로 전달해, 소비자가 "왜 취소됐는지"를 추론하지 않고 분기할 수 있게 한다.
 * 값 추가는 허용하되 기존 값의 의미 변경·삭제는 금지한다(ADR-0012 §46).
 *
 * <p>소비자는 <b>구 메시지에 이 필드가 없을 수 있음</b>을 전제로 동작해야 한다(부재 = 사유 불명).
 */
public enum OrderCancelReason {

    /** 사용자가 직접 취소했다. */
    USER_REQUESTED,

    /** 재고 예약 실패({@code stock.reservation.result} reserved=false)로 취소됐다. */
    RESERVATION_FAILED,

    /** 결제 실패({@code payment.failed})로 취소됐다. */
    PAYMENT_FAILED,

    /** 결제 타임아웃 또는 예약 lease 만료로 스케줄러가 취소했다. */
    TIMEOUT
}
