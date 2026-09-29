package com.peekcart.gateway.ratelimit;

/**
 * Rate limiter 백엔드(Redis) 장애 — <b>fail-closed</b> 로 요청을 거부하되 429 가 아니라
 * <b>503</b> 으로 분류한다(ADR-0013 D3).
 *
 * <p>"한도 초과(429)" 와 "판정 불가(503)" 는 원인이 다르다. 후자를 429 로 내보내면 클라이언트가
 * 백오프 후 재시도해도 상황이 바뀌지 않고, 운영에서 실제 장애가 정상적인 스로틀링으로 보인다.
 */
public class RateLimiterUnavailableException extends RuntimeException {

    public RateLimiterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
