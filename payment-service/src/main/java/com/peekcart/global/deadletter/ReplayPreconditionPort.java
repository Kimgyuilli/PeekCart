package com.peekcart.global.deadletter;

/**
 * replay 전 <b>로컬 도메인 상태</b> 사전조건 (ADR-0020 §D5-2 축 5 · 구현 ④-c-2b-4a P19).
 *
 * <p>정책이 {@code preconditionRequired} 인 {@code (소유 서비스, 토픽)} 쌍에서만 불린다. 조회는
 * <b>자기 서비스 DB 안에서만</b> 한다 — DB-per-service 라 남의 aggregate 를 볼 수 없고, 보려 하면
 * 그 순간 원격 DB 결합이 된다.
 *
 * <p><b>구현이 없으면 거부한다</b>(fail-closed). 사전조건이 필요한 토픽인데 판정기가 없다는 것은
 * 배선 누락이지 "조건 없음" 이 아니다.
 *
 * <p>호출 시점에 호출자는 <b>도메인 aggregate 를 비관적으로 잠근 상태</b>여야 한다 — 판정과 claim
 * 사이에 상태가 바뀌면 정책이 막으려던 재적용이 그대로 일어난다(계획 리뷰 3R #6).
 */
public interface ReplayPreconditionPort {

    /**
     * @param topic   원본 업무 토픽
     * @param payload 원본 레코드 payload 전문
     * @return 거부 사유. 통과면 {@code null}
     */
    String reject(String topic, String payload);
}
