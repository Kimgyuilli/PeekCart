package com.peekcart.payment.application;

import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.PaymentApproval;
import com.peekcart.payment.domain.model.PaymentStatus;
import com.peekcart.payment.infrastructure.toss.ApprovalExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 결제 승인을 처리하는 애플리케이션 서비스 (ADR-0023 D1).
 *
 * <p><b>이 클래스에 트랜잭션이 없다.</b> 승인은 T1(claim·키 커밋) → PG 호출 → T2(확정) 세 구간이며,
 * T1/T2 는 {@link PaymentApprovalService} 의 독립 트랜잭션이고 PG 호출은 <b>그 사이</b>에서 일어난다.
 * 하나의 트랜잭션으로 묶으면 PG 성공 후 커밋 실패 시 외부 과금만 남고 로컬이 전부 롤백되며,
 * 실제 {@code paymentKey} 까지 되돌아가 사후 확인 수단이 사라진다(D-020 · ADR-0023 C1/C2).
 *
 * <p>분해의 목적은 비동기화가 아니다 — 세 구간 모두 사용자 요청 안에서 동기로 돈다. 바뀐 것은
 * <b>커밋 경계가 PG 호출 앞뒤로 쪼개졌다</b>는 것뿐이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentCommandService {

    private final PaymentApprovalService approvalService;
    private final ApprovalExecutor approvalExecutor;
    private final PaymentQueryService paymentQueryService;

    /**
     * 결제를 승인한다. 소유자·예약 확정·취소 게이트는 모두 payment-로컬 상태로 검증하고
     * (OrderPort 동기 호출 제거), 결제 시작은 {@code payment.requested} 이벤트로 Order 에 알린다.
     *
     * @return 확정 후 결제 상세. {@code PENDING} 이면 결과 불명이며 reconciliation 이 종결시킨다
     * @throws PaymentException 결제 미존재 {@code PAY-003}, 금액 불일치 {@code PAY-001},
     *                          소유자 불일치 {@code PAY-007}, 예약 미확정 {@code PAY-008},
     *                          결제 불가 상태 {@code PAY-009}, lease 만료 {@code PAY-010},
     *                          진행 중인 승인 존재 {@code PAY-013}
     */
    public PaymentDetailDto confirmPayment(Long userId, ConfirmPaymentCommand command) {
        // T1 — 게이트 검증 + 실제 paymentKey + 원장 fence 를 커밋한다(커밋돼야 사후 조회가 가능하다).
        PaymentApproval approval = approvalService.beginApproval(
                userId, command.orderId(), command.paymentKey(), command.amount());

        // PG 호출 — 트랜잭션 밖. 안정 멱등키로 1회만 부른다(ADR-0023 D4).
        ApprovalExecutor.CallResult result = approvalExecutor.execute(approval);

        // T2 — claim 당시 generation 을 넘겨 소유권이 넘어갔다면 확정이 무시되게 한다.
        PaymentStatus status = approvalService.finalizeApproval(
                command.orderId(), approval.getGeneration(), result.outcome(), result.attempts());

        if (status == PaymentStatus.PENDING) {
            log.warn("승인 결과 불명 — 사용자에게 확인 중으로 응답, orderId={}", command.orderId());
        }
        return paymentQueryService.getPaymentByOrderId(userId, command.orderId());
    }
}
