package com.peekcart.global.outbox;

/**
 * outbox 레코드 종류 판별자 (ADR-0020 D3).
 *
 * <p>DB 컬럼은 <b>DEFAULT 없는 NOT NULL</b> 이다. expand(nullable) → deploy → backfill → contract 순서를 거쳤다.
 * <ul>
 *   <li><b>NOT NULL</b> — 신버전 writer 는 두 값 중 하나를 항상 명시한다. 값을 빠뜨린 INSERT 는 strict 모드에서
 *       실패한다. INSERT IGNORE·non-strict 는 빈 문자열로 강등할 수 있어 그 writer 를 막는 근거로 쓰지 않는다.</li>
 *   <li><b>DEFAULT 부재</b> — {@code DEFAULT 'DOMAIN'} 을 두면 writer 가 판별자를 빠뜨려도
 *       DB 가 조용히 도메인으로 분류한다. 누락을 실패시키려던 명시적 kind 계약이 그 자리에서 약해진다.</li>
 * </ul>
 */
public enum OutboxRecordKind {

    /** 도메인 이벤트. */
    DOMAIN,

    /** DLQ replay 재발행. 목적지 좌표를 스스로 싣는다. */
    REPLAY
}
