package com.peekcart.product.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.outbox.dto.ReservedItemPayload;
import com.peekcart.global.port.SlackPort;
import com.peekcart.product.domain.model.ReservationStatus;
import com.peekcart.product.domain.model.StockReservation;
import com.peekcart.product.domain.repository.StockReservationRepository;
import com.peekcart.product.infrastructure.outbox.ProductOutboxEventPublisher;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

@ServiceTest
@DisplayName("StockReservationService 단위 테스트")
class StockReservationServiceTest {

    @Mock StockReservationRepository reservationRepository;
    @Mock InventoryService inventoryService;
    @Mock InventoryLockFacade inventoryLockFacade;
    @Mock ProductOutboxEventPublisher publisher;
    @Mock SlackPort slackPort;

    StockReservationService service;

    private static final Long ORDER_ID = 100L;
    private static final String EVENT_ID = "evt-1";
    private final List<ReservedItemPayload> items = List.of(
            new ReservedItemPayload(1L, 2),
            new ReservedItemPayload(2L, 3));

    @BeforeEach
    void setUp() {
        service = new StockReservationService(reservationRepository, inventoryService,
                inventoryLockFacade, publisher, new ObjectMapper(), slackPort);
    }

    @Test
    @DisplayName("reserve: 전 품목 재고 충분하면 일괄 차감 후 reserved=true 발행")
    void reserve_allAvailable_decrementsAndPublishesReserved() {
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());
        given(inventoryService.hasSufficientStock(anyLong(), anyInt())).willReturn(true);

        service.reserve(ORDER_ID, EVENT_ID, items);

        then(inventoryLockFacade).should().decreaseStock(1L, 2);
        then(inventoryLockFacade).should().decreaseStock(2L, 3);
        then(reservationRepository).should().save(any(StockReservation.class));
        then(publisher).should().publishStockReservationResult(ORDER_ID, true, items, null);
    }

    @Test
    @DisplayName("reserve: 일부 품목 부족이면 차감 0건 + reserved=false (all-or-nothing)")
    void reserve_insufficientStock_noDecrement_publishesFalse() {
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());
        given(inventoryService.hasSufficientStock(1L, 2)).willReturn(true);
        given(inventoryService.hasSufficientStock(2L, 3)).willReturn(false);

        service.reserve(ORDER_ID, EVENT_ID, items);

        then(inventoryLockFacade).should(never()).decreaseStock(anyLong(), anyInt());
        then(publisher).should().publishStockReservationResult(ORDER_ID, false, items, "OUT_OF_STOCK");
    }

    @Test
    @DisplayName("reserve: 빈 items 는 예약 성공으로 오수렴하지 않고 reserved=false(INVALID_ITEMS)")
    void reserve_emptyItems_rejected() {
        service.reserve(ORDER_ID, EVENT_ID, List.of());

        then(inventoryLockFacade).should(never()).decreaseStock(anyLong(), anyInt());
        then(publisher).should().publishStockReservationResult(eq(ORDER_ID), eq(false), any(), eq("INVALID_ITEMS"));
    }

    @Test
    @DisplayName("reserve: 취소 선도착 tombstone 이면 차감 skip + reserved=false(CANCELLED)")
    void reserve_tombstone_skipsDecrement() {
        given(reservationRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(StockReservation.cancelTombstone(ORDER_ID)));

        service.reserve(ORDER_ID, EVENT_ID, items);

        then(inventoryLockFacade).should(never()).decreaseStock(anyLong(), anyInt());
        then(reservationRepository).should(never()).save(any(StockReservation.class));
        then(publisher).should().publishStockReservationResult(ORDER_ID, false, items, "CANCELLED");
    }

    @Test
    @DisplayName("reserve: 이미 예약된 주문이면 멱등 no-op")
    void reserve_alreadyReserved_idempotentNoop() {
        given(reservationRepository.findByOrderId(ORDER_ID))
                .willReturn(Optional.of(StockReservation.reserved(ORDER_ID, "[]", EVENT_ID)));

        service.reserve(ORDER_ID, EVENT_ID, items);

        then(inventoryLockFacade).should(never()).decreaseStock(anyLong(), anyInt());
        then(publisher).should(never()).publishStockReservationResult(anyLong(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("release: RESERVED → RELEASED CAS 1건 성공 시 재고 복구")
    void release_reserved_casSucceeds_restores() {
        StockReservation reserved = StockReservation.reserved(ORDER_ID,
                "[{\"productId\":1,\"quantity\":2},{\"productId\":2,\"quantity\":3}]", EVENT_ID);
        given(reservationRepository.markReleasedIfReserved(ORDER_ID)).willReturn(1);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(reserved));

        service.release(ORDER_ID);

        then(inventoryService).should().restoreStock(1L, 2);
        then(inventoryService).should().restoreStock(2L, 3);
    }

    @Test
    @DisplayName("release: CAS 0건 + 원장 없음이면 cancel-before-create tombstone 기록 (복구 0)")
    void release_noRow_insertsTombstone() {
        given(reservationRepository.markReleasedIfReserved(ORDER_ID)).willReturn(0);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

        service.release(ORDER_ID);

        then(inventoryService).should(never()).restoreStock(anyLong(), anyInt());
        then(reservationRepository).should().save(any(StockReservation.class));
    }

    @Test
    @DisplayName("release: CAS 0건 + 이미 종결 상태(FAILED/RELEASED)면 멱등 no-op (double-release 방지)")
    void release_alreadyTerminal_noop() {
        // 이미 처리된 원장(예: 재고부족 FAILED, 또는 직전 release 로 RELEASED) — 재고 복구하지 않는다
        StockReservation terminal = StockReservation.failed(ORDER_ID, "[]", EVENT_ID);
        given(reservationRepository.markReleasedIfReserved(ORDER_ID)).willReturn(0);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(terminal));

        service.release(ORDER_ID);

        then(inventoryService).should(never()).restoreStock(anyLong(), anyInt());
        then(reservationRepository).should(never()).save(any(StockReservation.class));
    }

    @Test
    @DisplayName("confirm: RESERVED → CONFIRMED CAS 1건 성공이면 확정 (조회/보상 없음)")
    void confirm_reservedCasSucceeds() {
        given(reservationRepository.markConfirmedIfReserved(ORDER_ID)).willReturn(1);

        service.confirm(ORDER_ID);

        then(reservationRepository).should(never()).findByOrderId(anyLong());
        then(reservationRepository).should(never()).markCompensatedIfAbsent(anyLong());
        then(slackPort).should(never()).send(anyString());
    }

    @Test
    @DisplayName("confirm: CAS 0건 + 이미 CONFIRMED 면 중복 payment.completed 멱등 no-op")
    void confirm_alreadyConfirmed_idempotentNoop() {
        StockReservation confirmed = mock(StockReservation.class);
        given(confirmed.getStatus()).willReturn(ReservationStatus.CONFIRMED);
        given(reservationRepository.markConfirmedIfReserved(ORDER_ID)).willReturn(0);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(confirmed));

        service.confirm(ORDER_ID);

        then(reservationRepository).should(never()).markCompensatedIfAbsent(anyLong());
        then(slackPort).should(never()).send(anyString());
    }

    @Test
    @DisplayName("confirm: CAS 0건 + 원장 없음이면 transient 로 보고 예외 throw (consumer 재시도)")
    void confirm_noRow_throwsForRetry() {
        given(reservationRepository.markConfirmedIfReserved(ORDER_ID)).willReturn(0);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(ORDER_ID))
                .isInstanceOf(IllegalStateException.class);

        then(reservationRepository).should(never()).markCompensatedIfAbsent(anyLong());
        then(slackPort).should(never()).send(anyString());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"RELEASED", "CANCEL_REQUESTED", "FAILED"})
    @DisplayName("confirm: CAS 0건 + 비-CONFIRMED 종결 원장(결제됐으나 재고 미확정)이면 최초 1회 보상 알림")
    void confirm_paidButUnreserved_compensatesOnce(ReservationStatus status) {
        StockReservation reservation = mock(StockReservation.class);
        given(reservation.getStatus()).willReturn(status);
        given(reservationRepository.markConfirmedIfReserved(ORDER_ID)).willReturn(0);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(reservation));
        given(reservationRepository.markCompensatedIfAbsent(ORDER_ID)).willReturn(1);

        service.confirm(ORDER_ID);

        then(slackPort).should().send(anyString());
    }

    @ParameterizedTest
    @EnumSource(value = ReservationStatus.class, names = {"RELEASED", "CANCEL_REQUESTED", "FAILED"})
    @DisplayName("confirm: 보상 marker 가 이미 있으면(CAS 0건) 알림 중복 발송 안 함")
    void confirm_alreadyCompensated_noDuplicateAlert(ReservationStatus status) {
        StockReservation reservation = mock(StockReservation.class);
        given(reservation.getStatus()).willReturn(status);
        given(reservationRepository.markConfirmedIfReserved(ORDER_ID)).willReturn(0);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(reservation));
        given(reservationRepository.markCompensatedIfAbsent(ORDER_ID)).willReturn(0);

        service.confirm(ORDER_ID);

        then(slackPort).should(never()).send(anyString());
    }

    @Test
    @DisplayName("release: 확정(CONFIRMED) 후 release 는 CAS 0건이라 재고 복구 안 함 (판매분 보호)")
    void release_afterConfirm_noRestore() {
        StockReservation confirmed = mock(StockReservation.class);
        given(confirmed.getStatus()).willReturn(ReservationStatus.CONFIRMED);
        given(reservationRepository.markReleasedIfReserved(ORDER_ID)).willReturn(0);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(confirmed));

        service.release(ORDER_ID);

        then(inventoryService).should(never()).restoreStock(anyLong(), anyInt());
        then(reservationRepository).should(never()).save(any(StockReservation.class));
    }
}
