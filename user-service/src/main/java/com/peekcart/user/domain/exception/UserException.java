package com.peekcart.user.domain.exception;

import com.peekcart.global.exception.BusinessException;
import com.peekcart.global.exception.ErrorCode;

/**
 * 회원 도메인에서 발생하는 비즈니스 예외.
 */
public class UserException extends BusinessException {
    public UserException(ErrorCode errorCode) {
        super(errorCode);
    }
}
