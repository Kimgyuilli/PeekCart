package com.peekcart.global.exception;

import lombok.Getter;

/**
 * 비즈니스 규칙 위반 시 발생하는 예외의 최상위 클래스.
 * 각 도메인 예외는 이 클래스를 상속한다.
 */
@Getter
public abstract class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    /**
     * {@link ErrorCode}에 정의된 메시지를 그대로 사용한다.
     */
    protected BusinessException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /**
     * 에러 코드와 함께 커스텀 메시지를 지정할 때 사용한다.
     */
    protected BusinessException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
