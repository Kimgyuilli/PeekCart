package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.dto.ConfirmPaymentCommand;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import com.peekcart.payment.infrastructure.outbox.PaymentOutboxEventPublisher;
import com.peekcart.payment.infrastructure.toss.TossConfirmResponse;
import com.peekcart.payment.infrastructure.toss.TossPaymentClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

/**
 * 결제 승인을 처리하는 애플리케이션 서비스.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class PaymentCommandService {

    private final PaymentRepository paymentRepository;
    private final TossPaymentClient tossPaymentClient;
    private final PaymentOutboxEventPublisher outboxEventPublisher;

    /**
     * 결제를 승인한다. 소유자·예약 확정·취소 게이트는 모두 payment-로컬 상태로 검증하고
     * (OrderPort 동기 호출 제거), 결제 시작은 {@code payment.requested} 이벤트로 Order 에 알린다.
     *
     * @throws PaymentException 결제 정보 미존재 {@code PAY-003}, 금액 불일치 {@code PAY-001},
     *                          소유자 불일치 {@code PAY-007}, 예약 미확정 {@code PAY-008}, 결제 불가 상태 {@code PAY-009}
     */
    public PaymentDetailDto confirmPayment(Long userId, ConfirmPaymentCommand command) {
        Payment payment = paymentRepository.findByOrderId(command.orderId())
                .orElseThrow(() -> new PaymentException(ErrorCode.PAY_003));

        payment.verifyOwner(userId);
        payment.validateAmount(command.amount());
        payment.ensureConfirmable();
        outboxEventPublisher.publishPaymentRequested(payment, userId);
        payment.assignPaymentKey(command.paymentKey());

        try {
            TossConfirmResponse response = tossPaymentClient.confirm(
                    command.paymentKey(), command.orderId().toString(), command.amount());
            payment.approve(response.method(), OffsetDateTime.parse(response.approvedAt()).toLocalDateTime());

            outboxEventPublisher.publishPaymentCompleted(payment, userId);
        } catch (Exception e) {
            log.error("Toss 결제 승인 실패 — orderId={}, paymentKey={}: {}",
                    command.orderId(), command.paymentKey(), e.getMessage());
            payment.fail();
            outboxEventPublisher.publishPaymentFailed(payment, userId);
        }

        return PaymentDetailDto.from(payment);
    }
}
