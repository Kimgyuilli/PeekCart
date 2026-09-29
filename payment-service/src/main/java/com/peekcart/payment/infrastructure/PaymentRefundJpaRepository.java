package com.peekcart.payment.infrastructure;

import com.peekcart.payment.domain.model.PaymentRefund;
import com.peekcart.payment.domain.model.RefundStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PaymentRefundJpaRepository extends JpaRepository<PaymentRefund, Long> {

    /**
     * fence 획득 (ADR-0018 D3). <b>단일 원자 INSERT</b> — 유니크 충돌을 예외가 아니라
     * 영향 행 수 0 으로 돌려받아야 소비 트랜잭션이 rollback-only 로 오염되지 않는다.
     *
     * <p>{@code ON DUPLICATE KEY UPDATE id = id} 를 쓰지 않는 이유: MySQL Connector/J 는 기본이
     * <b>found-rows</b> 시맨틱이라 값이 바뀌지 않은 중복도 <b>1</b> 로 보고한다 — 그러면 두 진입점이
     * 모두 "내가 fence 를 잡았다"고 판단한다(실측으로 확인). {@code INSERT IGNORE} 는 건너뛴 행을
     * 0 으로 보고해 승자를 정확히 1명으로 만든다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            INSERT IGNORE INTO payment_refunds
                (order_id, payment_key, user_id, amount, status, attempts, requested_at)
            VALUES (:orderId, :paymentKey, :userId, :amount, 'REQUESTED', 0, :now)
            """, nativeQuery = true)
    int insertRequestedIfAbsent(@Param("orderId") Long orderId,
                                @Param("paymentKey") String paymentKey,
                                @Param("userId") Long userId,
                                @Param("amount") long amount,
                                @Param("now") LocalDateTime now);

    /**
     * 신규 요청 claim CAS ({@code REQUESTED} 전용). <b>lease 가 만료된 CLAIMED 는 여기서 잡지 않는다</b> —
     * 그건 "PG 를 호출했는지 알 수 없는" 상태라 재호출 전에 조회로 진실을 확정해야 한다(ADR-0018 D3 a/b).
     *
     * <p>{@code generation} 을 올려 <b>fencing token</b> 을 만든다. lease 가 만료된 옛 owner 가 뒤늦게
     * 확정하려 해도 generation 이 달라 no-op 이 된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE payment_refunds
               SET status = 'CLAIMED', claimed_at = :now, generation = generation + 1
             WHERE order_id = :orderId
               AND status = 'REQUESTED'
            """, nativeQuery = true)
    int claimRequested(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    /**
     * 확정 대상을 reconciliation 이 claim 한다 — lease 만료 {@code CLAIMED} 또는 {@code UNRESOLVED}.
     * 한 인스턴스만 외부 호출하도록 소유권을 잡고 {@code generation} 을 올린다.
     *
     * <p>{@code UNRESOLVED} 도 <b>{@code CLAIMED} 로 전이</b>시킨다 — 상태를 그대로 두면 조회·재호출이
     * 진행 중인 행을 운영자가 수동 종결(UNRESOLVED 대상)할 수 있고, 그 뒤 실제 취소가 성공해도
     * 이미 terminal 이라 반영되지 않아 "환불은 됐는데 원장은 FAILED" 가 된다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE payment_refunds
               SET status = 'CLAIMED', claimed_at = :now, generation = generation + 1
             WHERE order_id = :orderId
               AND ((status = 'CLAIMED' AND claimed_at < :staleBefore)
                    OR (status = 'UNRESOLVED' AND (claimed_at IS NULL OR claimed_at < :staleBefore)))
            """, nativeQuery = true)
    int claimForReconcile(@Param("orderId") Long orderId,
                          @Param("staleBefore") LocalDateTime staleBefore,
                          @Param("now") LocalDateTime now);

    /** dispatcher 후보 — 신규 요청만. */
    @Query(value = """
            SELECT order_id FROM payment_refunds
             WHERE status = 'REQUESTED'
             ORDER BY requested_at
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findRequested(@Param("limit") int limit);

    /**
     * reconciliation 후보 — lease 만료 CLAIMED + UNRESOLVED.
     * <b>{@code claimed_at} 오름차순</b>이라 이번 실행에서 건드린 행은 뒤로 밀린다 —
     * 조회가 계속 실패하는 앞 배치가 뒤 배치를 굶기지 않는다(starvation 방지).
     */
    @Query(value = """
            SELECT order_id FROM payment_refunds
             WHERE (status = 'CLAIMED' AND claimed_at < :staleBefore)
                OR (status = 'UNRESOLVED' AND (claimed_at IS NULL OR claimed_at < :staleBefore))
             ORDER BY claimed_at IS NULL DESC, claimed_at, requested_at
             LIMIT :limit
            """, nativeQuery = true)
    List<Long> findReconcileCandidates(@Param("staleBefore") LocalDateTime staleBefore,
                                       @Param("limit") int limit);

    Optional<PaymentRefund> findByOrderId(Long orderId);

    /**
     * 확정용 조회 — <b>행 잠금</b>과 함께 읽는다. 잠금 없이 읽고 generation 을 비교하면 그 사이에
     * 다른 인스턴스가 claim 을 가져가는 TOCTOU 창이 남아, 만료된 owner 의 확정이 그대로 커밋된다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PaymentRefund r WHERE r.orderId = :orderId")
    Optional<PaymentRefund> findByOrderIdForUpdate(@Param("orderId") Long orderId);

    long countByStatus(RefundStatus status);

    @Query("SELECT MIN(r.requestedAt) FROM PaymentRefund r WHERE r.status = :status")
    Optional<LocalDateTime> findOldestRequestedAt(@Param("status") RefundStatus status);
}
