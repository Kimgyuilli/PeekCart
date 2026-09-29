package com.peekcart.global.outbox;

/**
 * outbox 레코드 종류 판별자 (ADR-0020 D3 · 구현 ④-c-2b-2 P10).
 *
 * <p>DB 컬럼은 nullable 이고 <b>DEFAULT 가 없다</b>. 두 성질의 이유가 다르다:
 * <ul>
 *   <li><b>nullable</b> — 롤링 배포 중 구버전 writer 가 이 컬럼을 모른 채 INSERT 한다.
 *       NOT NULL 을 먼저 걸면 그 INSERT 가 깨진다(expand → deploy → backfill → contract).</li>
 *   <li><b>DEFAULT 부재</b> — {@code DEFAULT 'DOMAIN'} 을 두면 신버전 writer 가 판별자를 빠뜨려도
 *       DB 가 조용히 도메인으로 분류한다. 누락을 실패시키려던 명시적 kind 계약이 그 자리에서 약해진다.</li>
 * </ul>
 *
 * <p>따라서 <b>읽기</b>는 {@code null} 을 {@link #DOMAIN} 으로 관대하게 해석하고,
 * <b>쓰기</b>는 두 값 중 하나를 항상 명시한다. 비대칭이 의도된 것이다.
 */
public enum OutboxRecordKind {

    /** 도메인 이벤트. {@code null}(구버전 writer) 도 이것으로 해석한다. */
    DOMAIN,

    /** DLQ replay 재발행. 목적지 좌표를 스스로 싣는다. */
    REPLAY
}
