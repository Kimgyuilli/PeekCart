package com.peekcart.payment.domain.exception;

import com.peekcart.global.exception.BusinessException;
import com.peekcart.global.exception.ErrorCode;

/**
 * Payment 도메인에서 발생하는 비즈니스 예외.
 */
public class PaymentException extends BusinessException {

    public PaymentException(ErrorCode errorCode) {
        super(errorCode);
    }
}
