package com.peekcart.global.deadletter;

/**
 * DLQ 원장의 <b>발행 축</b> 상태 (ADR-0020 §D6-1).
 *
 * <p>컬럼은 nullable 이며 <b>{@code NULL} 이 "replay 를 요청한 적 없음"</b> 이다 — 원장 행 절대다수가 그 상태다.
 *
 * <p><b>어느 값도 terminal 이 아니다.</b> {@link #PUBLISHED} 여도 사건은 미결로 남고 backlog 에 계속 잡힌다
 * (§D6-2). 사건의 종결은 {@link DeadLetterStatus#RESOLVED}/{@link DeadLetterStatus#DISCARDED} 로만 이루어진다.
 *
 * <p>전이 주체는 <b>2종</b>이다 (ADR-0022 §D4 — ADR-0020 §D6-4 의 "reconciler 1종" 을 부분 무효화):
 * <b>reconciler</b> 가 정상 종착을 맡고, {@link #PUBLISH_UNKNOWN} 만 <b>운영자의 단방향 override</b> 로
 * 들어온다. 관리 API 가 <b>만드는</b> 값은 여전히 {@link #REQUESTED} 뿐이다 — override 는 새 발행을
 * 개시하지 않고 <b>교착에서 빠져나오기만</b> 한다.
 */
public enum PublicationStatus {

    /** 관리 API 가 재발행을 요청했다. 아직 발행 여부를 모른다. 이 상태에서는 사건을 종결할 수 없다(I-1). */
    REQUESTED,

    /** broker ack 를 받았다. <b>사건 해소가 아니다.</b> */
    PUBLISHED,

    /** outbox 재시도가 소진됐다. 재요청이 허용된다. */
    PUBLISH_FAILED,

    /**
     * <b>발행 여부를 끝내 확인할 수 없다</b> — 운영자가 {@link #REQUESTED} 교착을 해제한 상태 (ADR-0022 §D4).
     *
     * <p><b>왜 필요한가</b>: 원장이 가리키는 outbox 행이 사라지면 reconciler 는 <b>강등하지 않고 남긴다</b>
     * (부재는 실패의 증거가 아니다 — 발행됐는데 행만 지워졌을 수 있다). 그러면 {@link #REQUESTED} 가
     * 스스로 해소되지 않고, I-1 때문에 사건 종결도 막히며, drain ⓐ' 도 영원히 0 이 되지 않는다.
     * fail-closed 가 아니라 <b>탈출구 없는 교착</b>이다.
     *
     * <p><b>단방향이다.</b> {@link #REQUESTED} 에서만 들어오고, 여기서 나가는 전이는 없다.
     * 재요청도 열리지 않는다 — claim 조건이 allow-list({@code NULL} 또는 {@link #PUBLISH_FAILED})라
     * 이 값은 자동으로 거부된다. 발행 여부를 모르는 건을 다시 발행하면 중복 소비 위험을 모르는 채로 지는 것이다.
     *
     * <p><b>사건 종결은 허용된다</b> — I-1 이 막는 것은 {@link #REQUESTED} 이고, 이 값은 이미
     * "확인했고 더 기다릴 근거가 없다" 는 사람의 판정이 들어간 상태다. 막으면 교착이 상태만 바꿔 유지된다.
     */
    PUBLISH_UNKNOWN
}
