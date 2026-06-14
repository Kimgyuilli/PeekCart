package com.peekcart.global.auth;

/**
 * 인증된 사용자의 ID와 액세스 토큰을 담는 컨텍스트 객체.
 * {@link LoginUserArgumentResolver}가 {@code SecurityContext}에서 추출해 컨트롤러 파라미터로 주입한다.
 */
public record LoginUser(Long userId, String accessToken) {
}
