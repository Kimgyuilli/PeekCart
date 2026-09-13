package com.peekcart.product.domain.repository;

import com.peekcart.product.domain.model.StockReservation;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 재고 예약 원장 리포지터리 인터페이스 (ADR-0012 D3).
 */
public interface StockReservationRepository {

    StockReservation save(StockReservation reservation);

    Optional<StockReservation> findByOrderId(Long orderId);

    /**
     * {@code RESERVED → RELEASED} 원자 CAS. 복구 권한은 이 전이가 1건 성공할 때만 부여된다(double-release 방지).
     *
     * @return 전이된 행 수 (0 또는 1)
     */
    int markReleasedIfReserved(Long orderId);

    /**
     * {@code RESERVED → CONFIRMED} 원자 CAS (strangler-3). 확정(commit) 은 이 전이가 1건 성공할 때만 부여된다.
     * CONFIRMED 는 종결 상태라 이후 {@link #markReleasedIfReserved}(RESERVED 조건) 가 자연히 no-op 이 된다.
     *
     * @return 전이된 행 수 (0 또는 1)
     */
    int markConfirmedIfReserved(Long orderId);

    /**
     * commit-실패 보상 1회성 marker 원자 CAS. {@code compensated_at} 이 비어있을 때만 채워 1건을 반환한다.
     * orderId 기준 멱등 — <b>상류 재발행(새 eventId, ADR-0012 D5 우회 경로)</b> 으로 confirm 이 재실행돼도
     * 보상 알림이 중복 발송되지 않는다. DLQ replay 는 {@code eventId} 를 보존하므로 여기가 아니라
     * {@code processed_events} 가 막는다(④-c-2b-4b 정정).
     *
     * <p>{@code detectedAt} 을 호출자가 넘기는 이유: 같은 값이 {@code stock.compensation.requested}
     * payload 의 {@code detectedAt} 으로도 실려, 원장의 감지 시각과 이벤트의 감지 시각이
     * <b>같은 사실의 두 기록</b>임이 코드로 드러난다(ADR-0018 D1 원자성 불변식).
     *
     * @return 마킹된 행 수 (0 또는 1; 1 = 최초 보상)
     */
    int markCompensatedIfAbsent(Long orderId, LocalDateTime detectedAt);

    /**
     * lease 가 만료된 {@code RESERVED} 예약을 조회한다 (계획 P4 sweeper).
     * {@code expiresAt} 이 null 인 행(lease 미부여 기존 행)은 제외한다 — 회수 근거가 없으므로 안전측.
     *
     * @param cutoff  이 시각 이전에 만료된 예약만 대상 (= now - sweeperGrace)
     * @param limit   1회 실행당 회수 상한
     */
    List<StockReservation> findExpiredReserved(LocalDateTime cutoff, int limit);
}
