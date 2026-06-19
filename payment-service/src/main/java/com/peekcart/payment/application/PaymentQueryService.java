package com.peekcart.payment.application;

import com.peekcart.global.exception.ErrorCode;
import com.peekcart.payment.application.dto.PaymentDetailDto;
import com.peekcart.payment.domain.exception.PaymentException;
import com.peekcart.payment.domain.model.Payment;
import com.peekcart.payment.domain.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 조회를 담당하는 애플리케이션 서비스.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PaymentQueryService {

    private final PaymentRepository paymentRepository;

    /**
     * @throws PaymentException 결제 정보 미존재 시 {@code PAY-003}, 본인 주문이 아니면 {@code PAY-007}
     */
    public PaymentDetailDto getPaymentByOrderId(Long userId, Long orderId) {
        Payment payment = paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentException(ErrorCode.PAY_003));
        payment.verifyOwner(userId);
        return PaymentDetailDto.from(payment);
    }
}
