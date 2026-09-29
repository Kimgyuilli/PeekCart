package com.peekcart.global.kafka;

import java.util.Set;
import java.util.UUID;

/**
 * DLQ replay 재발행 레코드에 싣는 상관 헤더의 <b>정본</b> (ADR-0021 · 구현 ④-c-2b-3a P14-a).
 *
 * <p><b>여기 하나에만 적는다.</b> 발행 측(진입점, ④-c-2b-4)과 판독 측({@link DlqHeaders})이 같은 문자열을
 * 각자 적으면, 한쪽이 갈라지는 순간 <b>모든 상관이 조용히 실패</b>해 재실패가 독립 incident 로 흩어진다.
 * 그 실패는 아무 것도 던지지 않으므로 테스트가 아니라 운영에서 발견된다.
 *
 * <p><b>헤더 값 자체는 신뢰하지 않는다</b>(ADR-0020 §D5-4). 이 값들은 비밀이 아니고 원본 producer 도 같은
 * 업무 토픽에 application 헤더를 쓸 수 있다. 헤더는 <b>어느 root 를 볼지 가리키는 지시자</b>일 뿐이고,
 * 실제 판정은 원장에 영속된 앵커와의 대조가 한다(ADR-0021 §D1).
 */
public final class ReplayHeaders {

    private ReplayHeaders() {
    }

    /** replay 시도 1건의 UUID. 원장 root 의 {@code last_replay_attempt_id} 와 대조한다. */
    public static final String ATTEMPT_ID = "pc-replay-attempt-id";

    /** 원장을 소유한 서비스({@link PeekcartService#prefix()}). 적재 서비스와 대조한다. */
    public static final String LEDGER_OWNER = "pc-replay-ledger-owner";

    /** 이 replay 가 표적한 업무 consumer group. 실제 실패 group·원장 앵커와 3자 대조한다. */
    public static final String TARGET_GROUP = "pc-replay-target-group";

    /** canonical incident root 의 원장 행 id. */
    public static final String ROOT_ID = "pc-replay-root-id";

    /**
     * replay 레코드에 실을 수 있는 헤더의 <b>전부</b>.
     *
     * <p>발행 측은 이 집합과 <b>정확히 같은</b> 키를 실어야 한다(부분집합이 아니다 — P14-b).
     * trace/user 헤더도, 표준 {@code DLT_*} 도 싣지 않는다: 후자를 실으면 재실패 시 원본 좌표가 덮여
     * 대조의 정본이 사라진다.
     */
    public static final Set<String> ALLOWED = Set.of(ATTEMPT_ID, LEDGER_OWNER, TARGET_GROUP, ROOT_ID);

    /**
     * 발행 전 헤더 맵이 계약을 만족하는지 본다 (P14-b).
     *
     * <p><b>키 집합 정확 일치 + 네 값 전부 유효</b>를 요구한다. "부분집합이면 통과" 로 두면
     * 헤더가 0~3개인 replay 가 그대로 발행되고, 재실패 시 상관 축이 없어 독립 incident 로 갈라진다 —
     * 발행 측에서 ADR-0020 §D5-4 를 깨는 경로다. 판독 측이 관대한 것과 대칭이 아닌 것이 의도다:
     * <b>쓰기는 엄격하게, 읽기는 관대하게.</b>
     *
     * @throws IllegalStateException 계약 위반. 삼키지 않는다 — 발행 실패로 드러나야 한다
     */
    public static void requireComplete(java.util.Map<String, String> headers) {
        if (!ALLOWED.equals(headers.keySet())) {
            throw new IllegalStateException(
                    "replay 헤더 키 집합이 계약과 다르다 — expected=" + sorted(ALLOWED)
                            + ", actual=" + sorted(headers.keySet()));
        }
        requireUuid(headers.get(ATTEMPT_ID));
        requireOwner(headers.get(LEDGER_OWNER));
        requireText(TARGET_GROUP, headers.get(TARGET_GROUP));
        requirePositiveLong(headers.get(ROOT_ID));
    }

    private static void requireText(String key, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("replay 헤더 " + key + " 가 비어 있다");
        }
    }

    private static void requireUuid(String value) {
        requireText(ATTEMPT_ID, value);
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("replay 헤더 " + ATTEMPT_ID + " 가 UUID 가 아니다: " + value, e);
        }
    }

    private static void requireOwner(String value) {
        requireText(LEDGER_OWNER, value);
        for (PeekcartService service : PeekcartService.values()) {
            if (service.prefix().equals(value)) {
                return;
            }
        }
        throw new IllegalStateException("replay 헤더 " + LEDGER_OWNER + " 가 알 수 없는 서비스다: " + value);
    }

    private static void requirePositiveLong(String value) {
        requireText(ROOT_ID, value);
        long parsed;
        try {
            parsed = Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("replay 헤더 " + ROOT_ID + " 가 정수가 아니다: " + value, e);
        }
        if (parsed <= 0) {
            throw new IllegalStateException("replay 헤더 " + ROOT_ID + " 가 양수가 아니다: " + value);
        }
    }

    private static String sorted(Set<String> keys) {
        return keys.stream().sorted().toList().toString();
    }
}
