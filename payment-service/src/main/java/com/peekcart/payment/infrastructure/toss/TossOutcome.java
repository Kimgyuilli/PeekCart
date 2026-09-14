package com.peekcart.payment.infrastructure.toss;

/**
 * PG 호출 결과 분류 (ADR-0018 D5). 분류를 클라이언트 경계에 두는 이유는 HTTP status·PG 오류코드
 * 해석이 도메인 로직이 아니라 외부 연동 지식이기 때문이다.
 *
 * @param kind        분류
 * @param code        PG 오류코드(있을 때)
 * @param rawResponse 응답 원문(감사·조회 근거)
 */
public record TossOutcome(Kind kind, String code, String rawResponse) {

    public enum Kind {
        /** 취소 성공 확정. */
        SUCCEEDED,
        /** 재시도로 상태가 바뀌지 않는 영구 실패. */
        PERMANENT_FAILURE,
        /** 네트워크·5xx·타임아웃 — 재시도 대상. */
        TRANSIENT,
        /**
         * 이미 취소됨. <b>실패가 아니다</b> — 이전 호출이 성공하고 응답만 유실됐거나 외부에서
         * 취소된 경우이므로, 조회로 금액을 확인해 성공/실패를 가른다(ADR-0018 D5).
         */
        ALREADY_CANCELED,
        /**
         * 이미 승인됨 (ADR-0023 D3). {@code ALREADY_CANCELED} 와 같은 구조 — 실패가 아니라
         * <b>조회로 판정할 입력</b>이다. 별도 값으로 두는 이유는 감사 로그의 {@code code} 가
         * 실제로 일어난 일을 가리켜야 하기 때문이다(취소와 승인은 다른 사건이다).
         */
        ALREADY_PROCESSED,
        /** 결과 불명(재시도 소진·응답 유실). */
        UNKNOWN
    }

    public static TossOutcome succeeded(String rawResponse) {
        return new TossOutcome(Kind.SUCCEEDED, null, rawResponse);
    }

    public static TossOutcome permanentFailure(String code, String rawResponse) {
        return new TossOutcome(Kind.PERMANENT_FAILURE, code, rawResponse);
    }

    public static TossOutcome transient_(String code, String rawResponse) {
        return new TossOutcome(Kind.TRANSIENT, code, rawResponse);
    }

    public static TossOutcome alreadyCanceled(String rawResponse) {
        return new TossOutcome(Kind.ALREADY_CANCELED, "ALREADY_CANCELED", rawResponse);
    }

    public static TossOutcome alreadyProcessed(String rawResponse) {
        return new TossOutcome(Kind.ALREADY_PROCESSED, "ALREADY_PROCESSED_PAYMENT", rawResponse);
    }

    public static TossOutcome unknown(String message) {
        return new TossOutcome(Kind.UNKNOWN, null, message);
    }
}
