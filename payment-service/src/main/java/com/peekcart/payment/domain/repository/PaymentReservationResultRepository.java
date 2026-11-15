package com.peekcart.payment.domain.repository;

import com.peekcart.payment.domain.model.PaymentReservationResult;

import java.util.Optional;

public interface PaymentReservationResultRepository {

    PaymentReservationResult save(PaymentReservationResult result);

    boolean existsByOrderId(Long orderId);

    /** 비관적 잠금 조회. 행이 없어도 키 구간을 잠가 예약 결과 consumer 의 marker 저장과 순서를 맞춘다 (D-112). */
    Optional<PaymentReservationResult> findByOrderIdForUpdate(Long orderId);

    /**
     * 잠금 조회로 받은 marker 를 PK 로 지운다. orderId 파생 삭제는 먼저 스냅숏 SELECT 를 해서,
     * 스냅숏 이후 커밋된 marker 를 못 찾고 아무것도 지우지 않는다(D-112 경합 테스트에서 확인).
     */
    void delete(PaymentReservationResult result);
}
