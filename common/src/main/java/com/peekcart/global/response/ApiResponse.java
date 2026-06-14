package com.peekcart.global.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 모든 API 성공 응답에 사용하는 공통 래퍼.
 * {@code data}가 {@code null}이면 JSON 직렬화 시 필드를 생략한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(T data) {

    /**
     * 응답 본문이 있는 성공 응답을 생성한다.
     *
     * @param data 응답 데이터
     * @return data를 감싼 ApiResponse
     */
    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(data);
    }

    /**
     * 응답 본문이 없는 성공 응답을 생성한다. (204 패턴 등)
     */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(null);
    }
}
