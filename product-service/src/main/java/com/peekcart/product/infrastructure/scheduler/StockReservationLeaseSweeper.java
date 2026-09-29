package com.peekcart.product.infrastructure.scheduler;

import com.peekcart.product.application.ReservationLeaseProperties;
import com.peekcart.product.application.StockReservationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 만료된 재고 예약 lease 를 회수하는 스케줄러 (ADR-0012 D3 ③).
 *
 * <p><b>안전망이지 정상 경로가 아니다.</b> 정상 흐름에서 예약을 되돌리는 주체는 Order 의 취소
 * ({@code order.cancelled} → release) 이고, 이 잡은 그 경로가 유실됐을 때만 동작한다. 그래서 lease
 * 만료 시각에 곧바로 회수하지 않고 {@code sweeperGrace} 만큼 기다린다 — Order 에게 우선권을 주지 않으면
 * 살아있는 주문의 재고를 뺏어 oversell 이 된다.
 *
 * <p>다중 replica 에서 중복 회수되지 않도록 {@link SchedulerLock} 으로 단일 실행을 보장한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@EnableConfigurationProperties({ReservationLeaseProperties.class, StockSchedulerProperties.class})
public class StockReservationLeaseSweeper {

    private final StockReservationService reservationService;

    @Scheduled(fixedDelayString = "${app.scheduler.stock.lease-sweep-delay}")
    @SchedulerLock(name = "stockReservationLeaseSweepJob",
            lockAtMostFor = "${app.scheduler.stock.lock-at-most-for}",
            lockAtLeastFor = "${app.scheduler.stock.lock-at-least-for}")
    public void sweep() {
        try {
            int reclaimed = reservationService.sweepExpiredLeases();
            if (reclaimed > 0) {
                log.warn("만료 예약 lease 회수 완료 — {}건 (정상 경로였다면 0이어야 한다)", reclaimed);
            }
        } catch (Exception e) {
            log.error("만료 예약 lease 회수 실패", e);
        }
    }
}
