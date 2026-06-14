package com.peekcart.global.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 전 도메인에서 공통으로 사용하는 에러 코드 정의.
 * 접두사 규칙: USR / PRD / ORD / PAY / SYS
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // User
    USR_001(HttpStatus.CONFLICT, "USR-001", "이미 사용 중인 이메일입니다."),
    USR_002(HttpStatus.UNAUTHORIZED, "USR-002", "이메일 또는 비밀번호가 올바르지 않습니다."),
    USR_003(HttpStatus.NOT_FOUND, "USR-003", "사용자를 찾을 수 없습니다."),
    USR_004(HttpStatus.UNAUTHORIZED, "USR-004", "유효하지 않은 토큰입니다."),
    USR_005(HttpStatus.UNAUTHORIZED, "USR-005", "만료된 토큰입니다."),

    // Product
    PRD_001(HttpStatus.NOT_FOUND, "PRD-001", "상품을 찾을 수 없습니다."),
    PRD_002(HttpStatus.CONFLICT, "PRD-002", "재고가 부족합니다."),
    PRD_003(HttpStatus.NOT_FOUND, "PRD-003", "카테고리를 찾을 수 없습니다."),
    PRD_004(HttpStatus.CONFLICT, "PRD-004", "재고 변경 충돌이 발생했습니다. 다시 시도해주세요."),

    // Order
    ORD_001(HttpStatus.NOT_FOUND, "ORD-001", "주문을 찾을 수 없습니다."),
    ORD_002(HttpStatus.BAD_REQUEST, "ORD-002", "이미 취소된 주문입니다."),
    ORD_003(HttpStatus.BAD_REQUEST, "ORD-003", "유효하지 않은 주문 상태 전이입니다."),
    ORD_004(HttpStatus.BAD_REQUEST, "ORD-004", "장바구니가 비어있습니다."),
    ORD_005(HttpStatus.BAD_REQUEST, "ORD-005", "수량은 1 이상이어야 합니다."),
    ORD_006(HttpStatus.NOT_FOUND, "ORD-006", "장바구니를 찾을 수 없습니다."),

    // Payment
    PAY_001(HttpStatus.BAD_REQUEST, "PAY-001", "결제 금액이 일치하지 않습니다."),
    PAY_002(HttpStatus.BAD_REQUEST, "PAY-002", "결제 타임아웃이 초과되었습니다."),
    PAY_003(HttpStatus.NOT_FOUND, "PAY-003", "결제 정보를 찾을 수 없습니다."),
    PAY_004(HttpStatus.BAD_REQUEST, "PAY-004", "유효하지 않은 결제 상태 전이입니다."),
    PAY_005(HttpStatus.BAD_REQUEST, "PAY-005", "결제 승인에 실패했습니다."),
    PAY_006(HttpStatus.UNAUTHORIZED, "PAY-006", "유효하지 않은 웹훅 서명입니다."),

    // System
    SYS_001(HttpStatus.INTERNAL_SERVER_ERROR, "SYS-001", "내부 서버 오류가 발생했습니다."),
    SYS_002(HttpStatus.SERVICE_UNAVAILABLE, "SYS-002", "외부 API 호출에 실패했습니다."),
    SYS_004(HttpStatus.FORBIDDEN, "SYS-004", "접근 권한이 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
