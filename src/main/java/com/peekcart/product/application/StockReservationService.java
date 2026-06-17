package com.peekcart.product.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.global.port.SlackPort;
import com.peekcart.product.domain.model.ReservationStatus;
import com.peekcart.product.domain.model.StockReservation;
import com.peekcart.product.domain.repository.StockReservationRepository;
import com.peekcart.product.infrastructure.outbox.ProductOutboxEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 재고 예약/복구 choreography 오케스트레이션 (ADR-0012 D3, strangler-1).
 * 호출자(consumer)의 {@code @Transactional} + 멱등 컨텍스트 안에서 실행되어
 * 차감/원장/발행의 원자성을 보장한다.
 */
@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class StockReservationService {

    private final StockReservationRepository reservationRepository;
    private final InventoryService inventoryService;
    private final InventoryLockFacade inventoryLockFacade;
    private final ProductOutboxEventPublisher publisher;
    private final ObjectMapper objectMapper;
    private final SlackPort slackPort;

    /**
     * order.created 수신 시 재고를 예약(차감)한다. all-or-nothing.
     * <ul>
     *   <li>tombstone(취소 선도착) 있으면 차감하지 않고 {@code reserved=false} 로 수렴</li>
     *   <li>전 품목 선검사 통과 시에만 일괄 차감 (부분 차감 금지) — race 로 차감 중 부족 시
     *       PRD-002 가 전파되어 트랜잭션 전체 롤백, 재시도 시 선검사가 막는다</li>
     * </ul>
     */
    public void reserve(Long orderId, String sourceEventId, List<ReservedItemPayload> items) {
        if (items == null || items.isEmpty()) {
            // malformed/빈 order.created — 빈 items 가 allMatch 로 예약 성공처럼 수렴하는 것을 막는다
            reservationRepository.save(StockReservation.failed(orderId, "[]", sourceEventId));
            publisher.publishStockReservationResult(orderId, false, List.of(), "INVALID_ITEMS");
            log.warn("빈 예약 품목 — reserved=false 수렴, orderId={}", orderId);
            return;
        }
        Optional<StockReservation> existing = reservationRepository.findByOrderId(orderId);
        if (existing.isPresent()) {
            if (existing.get().getStatus() == ReservationStatus.CANCEL_REQUESTED) {
                log.debug("취소 선도착 tombstone — 예약 skip, orderId={}", orderId);
                publisher.publishStockReservationResult(orderId, false, items, "CANCELLED");
            }
            // RESERVED/FAILED/RELEASED 는 이미 처리됨 → 멱등 no-op
            return;
        }

        boolean allAvailable = items.stream()
                .allMatch(i -> inventoryService.hasSufficientStock(i.productId(), i.quantity()));
        if (!allAvailable) {
            reservationRepository.save(StockReservation.failed(orderId, toJson(items), sourceEventId));
            publisher.publishStockReservationResult(orderId, false, items, "OUT_OF_STOCK");
            log.debug("재고 부족으로 예약 실패 — orderId={}", orderId);
            return;
        }

        for (ReservedItemPayload item : items) {
            inventoryLockFacade.decreaseStock(item.productId(), item.quantity());
        }
        reservationRepository.save(StockReservation.reserved(orderId, toJson(items), sourceEventId));
        publisher.publishStockReservationResult(orderId, true, items, null);
        log.debug("재고 예약 성공 — orderId={}", orderId);
    }

    /**
     * order.cancelled / payment.failed 수신 시 예약 재고를 복구한다.
     * 복구 권한은 {@code RESERVED → RELEASED} 원자 CAS 1건 성공일 때만 (double-release 방지, P1#2).
     * 예약(order.created) 도착 전이면 {@code CANCEL_REQUESTED} tombstone 을 남겨 이후 예약이 차감하지 않게 한다(P0#1).
     */
    public void release(Long orderId) {
        if (tryReleaseReserved(orderId)) {
            return;
        }
        Optional<StockReservation> existing = reservationRepository.findByOrderId(orderId);
        if (existing.isEmpty()) {
            try {
                reservationRepository.save(StockReservation.cancelTombstone(orderId));
                log.debug("예약 전 취소 — tombstone 기록, orderId={}", orderId);
            } catch (DataIntegrityViolationException race) {
                // 동시 예약이 원장 행을 선점 → 다시 release 시도
                tryReleaseReserved(orderId);
            }
        } else if (existing.get().getStatus() == ReservationStatus.RESERVED) {
            // 초기 CAS 이후 RESERVED 로 전이된 경우 한 번 더 시도
            tryReleaseReserved(orderId);
        }
        // 그 외(RELEASED/FAILED/CANCEL_REQUESTED) → 멱등 no-op
    }

    /**
     * payment.completed 수신 시 예약을 확정(commit)한다 (ADR-0012 ④, strangler-3).
     * <ul>
     *   <li>{@code RESERVED → CONFIRMED} 원자 CAS 1건 성공 → 확정. 이후 release 는 CONFIRMED 라 자연 no-op(판매분 보호)</li>
     *   <li>CAS 0건 + 원장 CONFIRMED → 중복 payment.completed 멱등 no-op</li>
     *   <li>CAS 0건 + 원장 없음 → 예약 원장 미도착 경합(transient) → 예외 throw 로 consumer 재시도(bounded), 한계 초과 시 DLQ</li>
     *   <li>CAS 0건 + 원장 RELEASED/CANCEL_REQUESTED/FAILED → 결제됐으나 재고 미확정(commit-실패 최악 경로) → 보상(환불 요청+운영 알림)</li>
     * </ul>
     */
    public void confirm(Long orderId) {
        if (reservationRepository.markConfirmedIfReserved(orderId) == 1) {
            log.debug("재고 예약 확정(commit) — orderId={}", orderId);
            return;
        }
        StockReservation existing = reservationRepository.findByOrderId(orderId)
                // 예약 원장 미도착 경합 — transient. consumer 재시도(bounded)로 수렴, 한계 초과 시 DLQ.
                .orElseThrow(() -> new IllegalStateException(
                        "예약 원장 미존재 — payment.completed 확정 재시도 필요: " + orderId));
        if (existing.getStatus() == ReservationStatus.CONFIRMED) {
            // 중복 payment.completed → 멱등 no-op
            return;
        }
        // RELEASED/CANCEL_REQUESTED/FAILED — 결제됐으나 재고 미확정 → 보상
        compensatePaidButUnreserved(orderId, existing.getStatus());
    }

    /**
     * commit-실패(PAID_BUT_UNRESERVED) 보상 (ADR-0012 ④). {@code orderId} 기준 1회성 marker 로
     * 멱등을 보장해 DLQ 재발행(새 eventId) 으로 confirm 이 재실행돼도 알림이 중복 발송되지 않는다.
     * 자동 환불 플로우는 미존재 — 운영 알림 + audit 마킹까지 수행하고 수동 환불로 수렴한다.
     */
    private void compensatePaidButUnreserved(Long orderId, ReservationStatus status) {
        if (reservationRepository.markCompensatedIfAbsent(orderId) == 1) {
            log.error("PAID_BUT_UNRESERVED — 결제 완료됐으나 재고 미확정(원장={}), 수동 환불 필요. orderId={}", status, orderId);
            slackPort.send(String.format(
                    "[보상 필요] PAID_BUT_UNRESERVED orderId=%d 원장상태=%s — 결제 완료됐으나 재고 미확정. 수동 환불 확인 요망.",
                    orderId, status));
        }
        // 이미 보상됨 → 멱등 no-op
    }

    private boolean tryReleaseReserved(Long orderId) {
        int updated = reservationRepository.markReleasedIfReserved(orderId);
        if (updated != 1) {
            return false;
        }
        StockReservation reservation = reservationRepository.findByOrderId(orderId)
                .orElseThrow(() -> new IllegalStateException("RELEASED 전이 후 원장 미존재: " + orderId));
        for (ReservedItemPayload item : fromJson(reservation.getItems())) {
            inventoryService.restoreStock(item.productId(), item.quantity());
        }
        log.debug("예약 재고 복구 완료 — orderId={}", orderId);
        return true;
    }

    private String toJson(List<ReservedItemPayload> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("예약 품목 직렬화 실패", e);
        }
    }

    private List<ReservedItemPayload> fromJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ReservedItemPayload>>() {});
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("예약 품목 역직렬화 실패", e);
        }
    }
}
