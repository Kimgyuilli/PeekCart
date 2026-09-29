package com.peekcart.global.outbox;

public enum OutboxEventStatus {
    PENDING,
    PUBLISHED,
    FAILED,

    /**
     * backfill 마이그레이션이 조립 중인 행 (구현 ④-c-1b).
     *
     * <p>backfill 은 payload 안의 {@code eventId} 를 {@code event_id} 컬럼과 같은 값으로 넣어야 해서
     * INSERT 와 envelope UPDATE 를 두 문장으로 나눈다. 그 사이의 행이 {@code PENDING} 이면
     * poller 가 <b>필수 필드가 없는 payload 를 발행하고 PUBLISHED 로 봉인</b>한다 — 롤링 배포 중
     * 구 인스턴스가 살아있으면 실제로 일어난다.
     *
     * <p>따라서 조립 중에는 이 상태로 두고, 2단계 UPDATE 가 payload 완성과 {@code PENDING} 전환을
     * <b>한 문장에서</b> 수행한다. <b>어떤 조회도 이 상태를 대상으로 하지 않는다</b> — 발행
     * ({@code status='PENDING'})·정리({@code status='PUBLISHED'})·backlog 게이지 전부 제외된다.
     * enum 에 값을 두는 이유는 매핑 없는 문자열이 DB 에 남아 엔티티 조회를 깨뜨리지 않게 하기 위함이다.
     */
    BACKFILL
}
