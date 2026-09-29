package com.peekcart.global.deadletter;

/**
 * DLQ 원장의 <b>사건(resolution) 축</b> 상태 (④-c-2a §2.6-A · ADR-0020 §D6-1).
 *
 * <p><b>발행 축은 여기 없다.</b> 재발행의 진행 상태는 {@link PublicationStatus} 가 별도 컬럼으로 갖는다 —
 * 단일 컬럼에 담으면 <i>발행 실패</i>와 <i>소비 재실패</i>가 같은 값이 되어 상반된 사실을 가리킨다.
 *
 * <p><b>소비 재실패도 상태값이 아니다</b>(ADR-0020 §D5-4). 재발행분이 또 실패하면 새 원장 행(자식)이 생기고,
 * 그 <b>존재 자체</b>가 사실이다. {@code CONSUMPTION_FAILED} 같은 값을 두지 않는다.
 *
 * <p>DB 컬럼은 enum 제약이 아니라 {@code VARCHAR} 이고 검증은 애플리케이션이 한다 — 2a 가 남긴 확장점 덕에
 * {@link #RESOLVED} 추가에 마이그레이션이 필요 없었다.
 */
public enum DeadLetterStatus {

    /** 적재 직후. 아직 사람이 보지 않았다. */
    OPEN,

    /** 운영자가 확인했다. 원인 조사 중이라는 뜻이며 해소를 뜻하지 않는다. */
    ACKED,

    /**
     * 운영자가 <b>해소를 확인</b>했다. 무엇을 근거로 확인했는지 기록이 필수다.
     *
     * <p>broker ack 로는 절대 도달하지 않는다 — 발행 성공은 실패했던 consumer 의 업무 처리 성공을
     * 증명하지 않는다(ADR-0020 §D6-2).
     */
    RESOLVED,

    /** 재처리하지 않기로 결정했다. 사유 기록이 필수다 — 근거 없는 종결을 만들지 않는다. */
    DISCARDED;

    /** 더 이상 전이하지 않는 상태인가. 단, 늦은 자식이 도착하면 재개방된다(ADR-0020 §D6-2b I-2). */
    public boolean isTerminal() {
        return this == DISCARDED || this == RESOLVED;
    }
}
