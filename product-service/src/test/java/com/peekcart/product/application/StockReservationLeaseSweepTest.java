package com.peekcart.product.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.peekcart.global.port.SlackPort;
import com.peekcart.product.domain.model.StockReservation;
import com.peekcart.product.infrastructure.metrics.ProductSagaMetrics;
import com.peekcart.product.domain.repository.StockReservationRepository;
import com.peekcart.product.infrastructure.outbox.ProductOutboxEventPublisher;
import com.peekcart.support.ServiceTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

/**
 * 예약 lease sweeper 단위 테스트 (계획 P4 · §5).
 *
 * <p>검증의 핵심은 "회수했다"가 아니라 <b>회수 판정 기준</b>이다 — cutoff 가 만료 시각이 아니라
 * {@code 만료 + 유예} 여야 Order 취소 경로가 우선권을 갖는다. 이 유예가 사라지면 살아있는 주문의
 * 재고를 뺏는 oversell 이 되므로 cutoff 계산을 직접 포착해 대조한다.
 */
@ServiceTest
@DisplayName("예약 lease sweeper 단위 테스트")
class StockReservationLeaseSweepTest {

    @Mock StockReservationRepository reservationRepository;
    @Mock InventoryService inventoryService;
    @Mock ProductOutboxEventPublisher publisher;
    @Mock SlackPort slackPort;

    StockReservationService service;

    private static final Duration TTL = Duration.ofMinutes(30);
    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final Long ORDER_ID = 100L;
    private static final String ITEMS_JSON = "[{\"productId\":1,\"quantity\":2}]";

    @BeforeEach
    void setUp() {
        ReservationLeaseProperties props = new ReservationLeaseProperties();
        props.setTtl(TTL);
        props.setSweeperGrace(GRACE);
        props.setSweeperBatchSize(200);
        service = new StockReservationService(reservationRepository, inventoryService,
                publisher, new ObjectMapper(), slackPort, props,
                new ProductSagaMetrics(new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("cutoff 는 '만료 시각'이 아니라 '만료 + 유예' 다 (Order 취소 경로 우선권)")
    void cutoffIncludesGrace() {
        given(reservationRepository.findExpiredReserved(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of());
        LocalDateTime before = LocalDateTime.now();

        service.sweepExpiredLeases();

        LocalDateTime after = LocalDateTime.now();
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        then(reservationRepository).should().findExpiredReserved(cutoff.capture(), anyInt());
        // cutoff = (호출 시각) - grace 구간 안에 들어와야 한다. 즉 "지금 막 만료된" 예약은 회수 대상이 아니다.
        assertThat(cutoff.getValue())
                .isBetween(before.minus(GRACE), after.minus(GRACE));
    }

    @Test
    @DisplayName("[SAGA-SWEEPER-LEASE-RECLAIM] 만료 예약을 회수하면 재고를 복구하고 운영 알림을 보낸다 (정상 경로면 0건이어야 하므로)")
    void reclaimsExpiredAndAlerts() {
        StockReservation expired = StockReservation.reserved(ORDER_ID, ITEMS_JSON, "evt-1", TTL);
        given(reservationRepository.findExpiredReserved(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of(expired));
        given(reservationRepository.markReleasedIfReserved(ORDER_ID)).willReturn(1);
        given(reservationRepository.findByOrderId(ORDER_ID)).willReturn(Optional.of(expired));

        int reclaimed = service.sweepExpiredLeases();

        assertThat(reclaimed).isEqualTo(1);
        then(inventoryService).should().restoreStock(1L, 2);
        then(slackPort).should().send(any(String.class));
    }

    @Test
    @DisplayName("[SAGA-SWEEPER-CAS-LOSS] CAS 가 지면(동시 release 선점) 회수하지 않고 재고도 건드리지 않는다 — 이중 복구 차단")
    void casLoses_noRestore_noAlert() {
        StockReservation expired = StockReservation.reserved(ORDER_ID, ITEMS_JSON, "evt-1", TTL);
        given(reservationRepository.findExpiredReserved(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of(expired));
        given(reservationRepository.markReleasedIfReserved(ORDER_ID)).willReturn(0);

        int reclaimed = service.sweepExpiredLeases();

        assertThat(reclaimed).isZero();
        then(inventoryService).should(never()).restoreStock(anyLong(), anyInt());
        then(slackPort).should(never()).send(any(String.class));
    }

    @Test
    @DisplayName("회수 0건이면 알림을 보내지 않는다 (정상 상태의 소음 방지)")
    void nothingExpired_noAlert() {
        given(reservationRepository.findExpiredReserved(any(LocalDateTime.class), anyInt()))
                .willReturn(List.of());

        assertThat(service.sweepExpiredLeases()).isZero();
        then(slackPort).should(never()).send(any(String.class));
    }
}
