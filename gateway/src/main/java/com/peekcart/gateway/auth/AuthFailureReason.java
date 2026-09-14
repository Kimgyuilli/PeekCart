package com.peekcart.gateway.auth;

/**
 * Gateway 가 요청을 거부한 사유 (S9 — ADR-0009 §Decision S9 · ADR-0024 D4).
 *
 * <p><b>왜 enum 인가</b>: {@code auth.failure} 의 {@code reason} 태그 값이 여기서만 나온다.
 * 예외 message 를 태그로 쓰면 토큰 파편·경로 등 사용자 입력이 라벨이 되어 카디널리티가 폭발한다.
 * 값 집합을 타입으로 닫아두면 새 사유를 추가할 때 이 파일을 반드시 거친다.
 *
 * <p><b>왜 두 문자열인가</b>: 클라이언트에게 돌려주는 {@link #wire()} 와 메트릭 태그 {@link #tag()} 를
 * 분리한다. 운영은 "서명이 깨졌는지 / 만료됐는지 / 폐기된 kid 인지"를 구분해야 하지만, 그 구분을
 * 응답으로 돌려주면 <b>토큰 검증 내부 상태를 요청자에게 알려주는 것</b>이 된다(위조 시도의 피드백 루프).
 * 그래서 401 계열 세부 사유는 응답에서 {@code invalid_token} 으로 합치고, 분해는 메트릭에만 남긴다.
 * PR4 이전의 응답 계약(헤더 값 5종)은 그대로 유지된다.
 */
public enum AuthFailureReason {

    /** 보호 경로에 토큰 미제시. */
    MISSING_TOKEN("missing_token", "missing_token"),
    /** JWT 형식이 아니거나 헤더 디코딩 실패. */
    MALFORMED("invalid_token", "malformed"),
    /** 서명 불일치 — 키는 찾았으나 검증 실패. */
    BAD_SIGNATURE("invalid_token", "bad_signature"),
    /** exp 경과. */
    EXPIRED("invalid_token", "expired"),
    /** JWKS 에 없는 kid — 위조 또는 폐기된 키. */
    UNKNOWN_KID("invalid_token", "unknown_kid"),
    /** alg allow-list 밖(RS256 아님). */
    ALG_NOT_ALLOWED("invalid_token", "alg_not_allowed"),
    /** exp 클레임 부재 — 무기한 토큰 거부. */
    MISSING_EXP("invalid_token", "missing_exp"),
    /** blacklist/family deny 적중 — 로그아웃·reuse 무효화된 토큰. */
    DENIED("invalid_token", "denied"),
    /** 사용자 토큰은 유효하나 정책상 내부 토큰 발행 거부(family-less 등). */
    INTERNAL_TOKEN_REFUSED("internal_token_refused", "internal_token_refused"),
    /** JWKS/Redis 장애 — 보안 판정 불가(503). */
    DEPENDENCY_UNAVAILABLE("dependency_unavailable", "dependency_unavailable"),
    /** rate limiter 백엔드 장애 — 한도 초과(429)가 아니라 판정 불가(503). */
    RATE_LIMITER_UNAVAILABLE("rate_limiter_unavailable", "rate_limiter_unavailable");

    private final String wire;
    private final String tag;

    AuthFailureReason(String wire, String tag) {
        this.wire = wire;
        this.tag = tag;
    }

    /** 응답 헤더({@code X-Auth-Failure-Reason})로 나가는 값 — 401 세부 사유는 합쳐진다. */
    public String wire() {
        return wire;
    }

    /** {@code auth.failure{reason=}} 태그 값 — 운영이 보는 분해된 사유. */
    public String tag() {
        return tag;
    }
}
