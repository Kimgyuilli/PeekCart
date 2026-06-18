package com.peekcart.product.domain.exception;

import com.peekcart.global.exception.BusinessException;
import com.peekcart.global.exception.ErrorCode;

/**
 * 상품 도메인에서 발생하는 비즈니스 예외.
 */
public class ProductException extends BusinessException {
    public ProductException(ErrorCode errorCode) {
        super(errorCode);
    }
}
