package com.peekcart.product.application.port;

/**
 * saga 경로 계측 포트.
 *
 * <p><b>왜 포트인가</b>: 계측 지점이 {@code StockReservationService}(application) 안에 있는데
 * Micrometer 구현을 직접 참조하면 의존 방향이 뒤집힌다 —
 * CLAUDE.md 의 {@code Presentation → Application → Domain ← Infrastructure} 규칙 위반이다.
 * {@code SlackPort} 가 같은 이유로 포트인 것과 같은 결이다.
 *
 * <p>구현은 {@code product.infrastructure.metrics.ProductSagaMetrics} 다.
 *
 * <p><b>호출 계약</b>: 전부 "실제 전이가 일어났을 때만" 부른다. 멱등 no-op(CAS 패자·중복 marker·
 * double-release)에서 부르면 메트릭이 실제 사건 수를 부풀린다.
 */
public interface SagaMetricsPort {

    /** 예약 성공 — 재고 차감과 원장 저장이 실제로 일어났을 때. */
    void reservationSucceeded();

    /** 예약 실패 — 재고 부족·빈 품목·취소 선도착 등 {@code reserved=false} 로 수렴한 경우. */
    void reservationFailed();

    /** 확정 CAS 1건 성공. 중복 {@code payment.completed} 의 멱등 no-op 은 제외. */
    void reservationConfirmed();

    /** 복구 CAS 1건 성공. double-release 는 제외. */
    void reservationReleased();

    /** sweeper 회수 — <b>건수만큼</b>. 실행당 1 이 아니다. */
    void sweeperReclaimed(int count);

    /** 보상 marker CAS 1건 성공. 이미 보상된 건의 no-op 은 제외. */
    void compensationDetected();
}
