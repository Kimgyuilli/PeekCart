package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.ApprovalStatus;
import com.peekcart.payment.domain.model.PaymentApproval;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentApprovalJpaRepository extends JpaRepository<PaymentApproval, Long> {

    /**
     * fence 획득 (ADR-0023 D2). <b>단일 원자 INSERT</b> — 유니크 충돌을 예외가 아니라 영향 행 수 0
     * 으로 돌려받아야 T1 이 rollback-only 로 오염되지 않는다.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id = id} 를 쓰지 않는 이유: MySQL Connector/J 는 기본이
     * found-rows 시맨틱이라 값이 바뀌지 않은 중복도 <b>1</b> 로 보고한다 — 두 진입점이 모두
     * "내가 fence 를 잡았다"고 판단하게 된다(환불 원장에서 실측으로 확인).
     *
     * <p>{@code generation} 이 1 에서 시작한다 — T1 자체가 첫 claim 이다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO payment_approvals
                (order_id, payment_key, user_id, amount, status, attempts, generation, claimed_at, requested_at)
            VALUES (:orderId, :paymentKey, :userId, :amount, 'CLAIMED', 0, 1, :now, :now)
            """, nativeQuery = true)
    int insertClaimedIfAbsent(@Param("orderId") Long orderId,
                              @Param("paymentKey") String paymentKey,
                              @Param("userId") Long userId,
                              @Param("amount") long amount,
                              @Param("now") LocalDateTime now);

    /**
     * 확정 대상을 reconciliation 이 claim 한다 — lease 만료 {@code CLAIMED} 또는 {@code UNRESOLVED}.
     * 한 인스턴스만 PG 조회를 하도록 소유권을 잡고 {@code generation} 을 올린다.
     *
     * <p><b>두 분기 모두 {@code claimed_at IS NULL} 을 포함한다</b>(계획 C-9). 웹훅 nudge 가 lease 를
     * 비우므로, {@code claimed_at < :staleBefore} 만 두면 nudge 된 행을 영원히 못 잡는다.
     * 신규 삽입은 {@code claimed_at = now} 라 여기에 걸리지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE payment_approvals
               SET status = 'CLAIMED', claimed_at = :now, generation = generation + 1
             WHERE order_id = :orderId
               AND ((status = 'CLAIMED' AND (claimed_at IS NULL OR claimed_at < :staleBefore))
                    OR (status = 'UNRESOLVED' AND (claimed_at IS NULL OR claimed_at < :staleBefore)))
            """, nativeQuery = true)
    int claimForReconcile(@Param("orderId") Long orderId,
                          @Param("staleBefore") LocalDateTime staleBefore,
                          @Param("now") LocalDateTime now);

    /**
     * reconciliation 후보.
     * <b>{@code claimed_at} 오름차순 · NULL 우선</b>이라 웹훅이 nudge 한 건이 먼저 잡히고,
     * 이번 실행에서 건드린 행은 뒤로 밀린다(starvation 방지).
     */
    @Query(value = """
            SELECT order_id FROM payment_approvals
             WHERE status IN ('CLAIMED', 'UNRESOLVED')
               AND (claimed_at IS NULL OR claimed_at < :staleBefore)
             ORDER BY claimed_at IS NULL DESC, claimed_at, requested_at
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findReconcileCandidates(@Param("staleBefore") LocalDateTime staleBefore,
                                       @Param("limit") int limit);

    /**
     * 웹훅 nudge (ADR-0023 D7) — {@code UNRESOLVED} 만 대상으로 lease 를 비운다.
     * PG 가 "상태가 바뀌었다" 고 알려준 것은 <b>지금 조회하면 답이 나온다</b>는 신호일 뿐이므로,
     * 상태 전이는 하지 않고 순회 우선순위만 올린다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE payment_approvals
               SET claimed_at = NULL
             WHERE payment_key = :paymentKey
               AND status = 'UNRESOLVED'
            """, nativeQuery = true)
    int nudgeUnresolvedByPaymentKey(@Param("paymentKey") String paymentKey);

    Optional<PaymentApproval> findByOrderId(Long orderId);

    /**
     * 확정용 조회 — <b>행 잠금</b>과 함께 읽는다. 잠금 없이 읽고 generation 을 비교하면 그 사이에
     * 다른 인스턴스가 claim 을 가져가는 TOCTOU 창이 남아, 만료된 owner 의 확정이 그대로 커밋된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM PaymentApproval a WHERE a.orderId = :orderId")
    Optional<PaymentApproval> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    long countByStatus(ApprovalStatus status);

    @Query("SELECT MIN(a.requestedAt) FROM PaymentApproval a WHERE a.status = :status")
    Optional<LocalDateTime> findOldestRequestedAt(@Param("status") ApprovalStatus status);
}
