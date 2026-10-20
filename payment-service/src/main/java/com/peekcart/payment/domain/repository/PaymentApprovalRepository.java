package com.peekcart.payment.domain.repository;

import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.PaymentApproval;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 승인 원장 repository (ADR-0023 D2).
 *
 * <p>fence 획득과 claim 은 <b>원자 쿼리</b>로만 제공한다 — 조회 후 판단하는 API 를 두면
 * 동시 진입에서 둘 다 통과하는 창이 생긴다.
 *
 * <p>신규 요청은 WAITING_STOCK으로 삽입한다. READY의 최초 dispatch는 원장 행 잠금으로,
 * CLAIMED/UNRESOLVED의 조회 claim은 조건부 UPDATE로 직렬화한다 (ADR-0034).
 */
public interface PaymentApprovalRepository {

    /**
     * 승인 원장을 fence 와 함께 {@code WAITING_STOCK} 로 생성한다 (단일 원자 INSERT).
     *
     * @return 1 = 이번 호출이 fence 를 획득 · 0 = 이미 존재(중복 승인 시도)
     */
    int insertWaitingIfAbsent(Long orderId, String paymentKey, String pgOrderId, Long userId, long amount, LocalDateTime now, LocalDateTime deadline);

    List<Long> findDispatchCandidates(LocalDateTime now, int limit);

    /** 확정 대상 claim (lease 만료 CLAIMED · UNRESOLVED, generation 증가). @return 1 = 획득 */
    int claimForReconcile(Long orderId, LocalDateTime staleBefore, LocalDateTime now);

    /** reconciliation 후보 — lease 만료 CLAIMED + UNRESOLVED + 웹훅 nudge 된 건. */
    List<Long> findReconcileCandidates(LocalDateTime staleBefore, int limit);

    /**
     * 웹훅 nudge (ADR-0023 D7) — {@code UNRESOLVED} 의 lease 를 비워 다음 순회 최우선으로 만든다.
     * {@code CLAIMED} 는 건드리지 않는다(진행 중인 호출과 경쟁시키지 않는다).
     *
     * @return 영향받은 행 수
     */
    int nudgeUnresolvedByPaymentKey(String paymentKey);

    Optional<PaymentApproval> findByOrderId(Long orderId);

    /** 확정용 조회 — 행 잠금과 함께 읽어 generation 검사의 TOCTOU 창을 없앤다. */
    Optional<PaymentApproval> findByOrderIdForUpdate(Long orderId);

    long countByStatus(ApprovalStatus status);

    /** 해당 상태의 가장 오래된 요청 시각 (미해결 backlog age 게이지용). */
    Optional<LocalDateTime> findOldestRequestedAt(ApprovalStatus status);
}
