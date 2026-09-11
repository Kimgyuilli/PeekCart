package com.peekcart.global.replay;

/**
 * 한 {@code (원장 소유 서비스, 업무 토픽)} 쌍의 replay 정책 (ADR-0020 §D5-2 축 5 · 구현 ④-c-2b-4a P19).
 *
 * @param id                   감사 기록용 식별자. 원장 {@code replay_policy} 에 {@code id:버전:판정} 으로 남는다
 * @param decision             기본 판정
 * @param preconditionRequired {@code true} 면 판정 전에 <b>로컬 도메인 상태 조회</b>를 거친다
 */
public record ReplayPolicy(String id, Decision decision, boolean preconditionRequired) {

    public enum Decision { ALLOW, DENY }

    public boolean allowed() {
        return decision == Decision.ALLOW;
    }
}
