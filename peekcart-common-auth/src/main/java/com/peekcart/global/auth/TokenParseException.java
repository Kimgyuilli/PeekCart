package com.peekcart.global.auth;

/**
 * JWT 파싱 또는 서명 검증 실패 시 발생하는 예외.
 * jjwt의 {@code JwtException}을 global 레이어에서 래핑하여
 * Application 레이어가 jjwt에 직접 의존하지 않도록 한다.
 */
public class TokenParseException extends RuntimeException {

    public TokenParseException(Throwable cause) {
        super(cause);
    }
}
