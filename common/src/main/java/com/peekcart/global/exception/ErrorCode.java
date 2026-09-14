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
    ORD_007(HttpStatus.CONFLICT, "ORD-007", "상품 가격 정보를 아직 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    ORD_008(HttpStatus.CONFLICT, "ORD-008", "재고 예약이 아직 확정되지 않았습니다. 잠시 후 다시 시도해주세요."),
    ORD_009(HttpStatus.CONFLICT, "ORD-009", "상품 정보를 아직 사용할 수 없습니다. 잠시 후 다시 시도해주세요."),
    ORD_010(HttpStatus.BAD_REQUEST, "ORD-010", "유효하지 않은 커서입니다."),
    ORD_011(HttpStatus.BAD_REQUEST, "ORD-011", "size 파라미터가 올바르지 않습니다."),
    ORD_012(HttpStatus.BAD_REQUEST, "ORD-012", "지원하지 않는 페이지네이션 파라미터입니다."),

    // Payment
    PAY_001(HttpStatus.BAD_REQUEST, "PAY-001", "결제 금액이 일치하지 않습니다."),
    PAY_002(HttpStatus.BAD_REQUEST, "PAY-002", "결제 타임아웃이 초과되었습니다."),
    PAY_003(HttpStatus.NOT_FOUND, "PAY-003", "결제 정보를 찾을 수 없습니다."),
    PAY_004(HttpStatus.BAD_REQUEST, "PAY-004", "유효하지 않은 결제 상태 전이입니다."),
    PAY_005(HttpStatus.BAD_REQUEST, "PAY-005", "결제 승인에 실패했습니다."),
    PAY_006(HttpStatus.UNAUTHORIZED, "PAY-006", "유효하지 않은 웹훅 서명입니다."),
    PAY_007(HttpStatus.NOT_FOUND, "PAY-007", "본인 주문의 결제 정보가 아닙니다."),
    PAY_008(HttpStatus.CONFLICT, "PAY-008", "재고 예약이 아직 확정되지 않아 결제를 진행할 수 없습니다. 잠시 후 다시 시도해주세요."),
    PAY_009(HttpStatus.CONFLICT, "PAY-009", "결제를 진행할 수 없는 주문 상태입니다."),
    PAY_010(HttpStatus.CONFLICT, "PAY-010", "재고 예약 유효기간이 만료되어 결제를 진행할 수 없습니다. 주문을 다시 시도해주세요."),
    PAY_011(HttpStatus.INTERNAL_SERVER_ERROR, "PAY-011", "환불 대상 결제의 사용자 정보가 없어 환불을 시작할 수 없습니다."),
    // PAY-012 는 실패가 아니다 (ADR-0023 D8) — 과금이 성립했을 수 있는 건을 PAY-005 로 돌려주면
    // 사용자에게 "결제 안 됐다"고 단언하게 된다. 결과는 reconciliation 이 확정한다.
    PAY_012(HttpStatus.CONFLICT, "PAY-012", "결제 결과를 확인 중입니다. 잠시 후 결제 내역을 확인해주세요."),
    PAY_013(HttpStatus.CONFLICT, "PAY-013", "이미 진행 중이거나 완료된 결제 승인입니다."),

    // System
    SYS_001(HttpStatus.INTERNAL_SERVER_ERROR, "SYS-001", "내부 서버 오류가 발생했습니다."),
    SYS_002(HttpStatus.SERVICE_UNAVAILABLE, "SYS-002", "외부 API 호출에 실패했습니다."),
    SYS_004(HttpStatus.FORBIDDEN, "SYS-004", "접근 권한이 없습니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
