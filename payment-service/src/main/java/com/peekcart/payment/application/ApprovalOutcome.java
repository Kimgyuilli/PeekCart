package com.peekcart.payment.application;

import java.time.LocalDateTime;

/**
 * 원장에 반영할 <b>확정된 승인 결과</b> (ADR-0023 D5).
 *
 * <p>PG 응답 분류({@code TossOutcome})와 분리한 이유는 환불과 같다 — {@code ALREADY_PROCESSED}
 * 같은 응답은 그 자체로 결과가 아니라 <b>조회로 확정해야 하는 입력</b>이다. 이 타입은 조회까지
 * 끝난 뒤의 결론만 담는다.
 *
 * <p>{@code method}/{@code approvedAt} 은 {@code SUCCEEDED} 일 때만 의미가 있다 —
 * {@code payments} 의 승인 전이에 필요한 값이며, 승인 응답과 조회 응답이 같은 필드를 준다.
 *
 * @param kind       확정 결론
 * @param code       실패 사유 코드(실패일 때)
 * @param detail     감사용 상세(PG 응답 원문 또는 오류 메시지)
 * @param method     결제 수단(성공일 때)
 * @param approvedAt PG 승인 시각(성공일 때)
 */
public record ApprovalOutcome(Kind kind, String code, String detail, String method, LocalDateTime approvedAt) {

    public enum Kind {
        SUCCEEDED,
        FAILED,
        /** 확정 불가 — 종결이 아니라 미해결로 남긴다. */
        UNRESOLVED
    }

    public static ApprovalOutcome succeeded(String method, LocalDateTime approvedAt, String detail) {
        return new ApprovalOutcome(Kind.SUCCEEDED, null, detail, method, approvedAt);
    }

    public static ApprovalOutcome failed(String code, String detail) {
        return new ApprovalOutcome(Kind.FAILED, code, detail, null, null);
    }

    public static ApprovalOutcome unresolved(String detail) {
        return new ApprovalOutcome(Kind.UNRESOLVED, null, detail, null, null);
    }
}
