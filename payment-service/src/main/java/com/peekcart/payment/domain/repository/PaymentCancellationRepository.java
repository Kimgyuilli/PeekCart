package com.peekcart.payment.domain.repository;

import com.peekcart.payment.domain.model.PaymentCancellation;

import java.util.Optional;

public interface PaymentCancellationRepository {

    PaymentCancellation save(PaymentCancellation cancellation);

    boolean existsByOrderId(Long orderId);

    /** 비관적 잠금 조회. 행이 없어도 키 구간을 잠가 취소 consumer 의 marker 저장과 순서를 맞춘다 (D-121). */
    Optional<PaymentCancellation> findByOrderIdForUpdate(Long orderId);

    /**
     * 잠금 조회로 받은 marker 를 PK 로 지운다. orderId 파생 삭제는 먼저 스냅숏 SELECT 를 해서,
     * 스냅숏 이후 커밋된 marker 를 못 찾고 아무것도 지우지 않는다(D-112, D-121).
     */
    void delete(PaymentCancellation cancellation);
}
