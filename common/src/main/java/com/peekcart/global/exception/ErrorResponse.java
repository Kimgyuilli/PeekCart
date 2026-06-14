package com.peekcart.global.exception;

import java.time.Instant;

/** 모든 에러 응답에 사용하는 공통 응답 포맷. */
public record ErrorResponse(int status, String code, String message, Instant timestamp) {}
