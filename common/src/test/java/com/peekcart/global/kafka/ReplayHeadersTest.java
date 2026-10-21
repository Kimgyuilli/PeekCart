package com.peekcart.global.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 발행 측 계약 (ADR-0021 D1).
 *
 * <p><b>판독 측과 대칭이 아닌 것이 의도다</b> — 쓰기는 엄격하게, 읽기는 관대하게.
 * 판독이 예외를 던지면 조작된 헤더 하나로 DLQ 적재를 막을 수 있지만, 발행이 관대하면
 * 상관 축이 빠진 replay 가 나가서 재실패가 독립 incident 로 갈라진다.
 */
@DisplayName("ReplayHeaders — 발행 전 헤더 계약")
class ReplayHeadersTest {

    private static final String ATTEMPT = "3f2a1b7c-8d9e-4a0b-9c1d-2e3f4a5b6c7d";

    private static Map<String, String> valid() {
        Map<String, String> m = new HashMap<>();
        m.put(ReplayHeaders.ATTEMPT_ID, ATTEMPT);
        m.put(ReplayHeaders.LEDGER_OWNER, "order");
        m.put(ReplayHeaders.TARGET_GROUP, "order-svc-payment-completed-group");
        m.put(ReplayHeaders.ROOT_ID, "4242");
        return m;
    }

    @Test
    @DisplayName("4종이 전부 유효하면 통과한다 — 항상 던지는 검사는 검사가 아니다")
    void acceptsCompleteHeaders() {
        assertThatCode(() -> ReplayHeaders.requireComplete(valid())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ALLOWED 는 정확히 4종이다")
    void allowlistIsExactlyFour() {
        assertThat(ReplayHeaders.ALLOWED).containsExactlyInAnyOrder(
                "pc-replay-attempt-id", "pc-replay-ledger-owner",
                "pc-replay-target-group", "pc-replay-root-id");
    }

    @Nested
    @DisplayName("키 집합이 정확히 일치하지 않으면 거부한다")
    class KeySet {

        /**
         * <b>부분집합 허용이 왜 위험한가</b>: poller 는 {@code replay_headers} 가 비면 빈 Map 을 돌려주고
         * blank 값은 조용히 생략한다. "부분집합이면 통과" 로 두면 헤더 0~3개짜리 replay 가 그대로 발행되고,
         * 재실패 시 상관 축이 없어 독립 incident 로 갈라진다 — 발행 측에서 계약을 깨는 경로다.
         */
        @Test
        @DisplayName("키가 하나라도 빠지면 거부 — 부분집합은 통과가 아니다")
        void rejectsMissingKey() {
            for (String key : ReplayHeaders.ALLOWED) {
                Map<String, String> headers = valid();
                headers.remove(key);
                assertThatThrownBy(() -> ReplayHeaders.requireComplete(headers))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("키 집합");
            }
        }

        @Test
        @DisplayName("빈 맵을 거부한다 — replay_headers 가 비었을 때의 경로")
        void rejectsEmpty() {
            assertThatThrownBy(() -> ReplayHeaders.requireComplete(Map.of()))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("allowlist 밖의 키가 섞이면 거부한다")
        void rejectsUnknownKey() {
            Map<String, String> headers = valid();
            headers.put("X-User-Id", "42");

            assertThatThrownBy(() -> ReplayHeaders.requireComplete(headers))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("키 집합");
        }

        /**
         * 표준 {@code DLT_*} 를 실으면 재실패 시 원본 좌표가 <b>덮여</b> 대조의 정본이 사라진다
         * (ADR-0020 §D3). allowlist 밖이라는 일반 규칙에 걸리지만, 그 이유가 다르므로 따로 고정한다.
         */
        @Test
        @DisplayName("표준 DLT_* 헤더를 거부한다 — 실으면 원본 좌표가 덮인다")
        void rejectsStandardDltHeaders() {
            Map<String, String> headers = valid();
            headers.put(KafkaHeaders.DLT_ORIGINAL_TOPIC, "payment.completed");

            assertThatThrownBy(() -> ReplayHeaders.requireComplete(headers))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("값이 유효하지 않으면 거부한다")
    class Values {

        @Test
        @DisplayName("blank 값을 거부한다 — 키만 있고 값이 비면 헤더가 조용히 생략된다")
        void rejectsBlank() {
            for (String key : ReplayHeaders.ALLOWED) {
                Map<String, String> headers = valid();
                headers.put(key, "   ");
                assertThatThrownBy(() -> ReplayHeaders.requireComplete(headers))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(key);
            }
        }

        @Test
        @DisplayName("attempt-id 가 UUID 가 아니면 거부한다")
        void rejectsNonUuidAttempt() {
            Map<String, String> headers = valid();
            headers.put(ReplayHeaders.ATTEMPT_ID, "not-a-uuid");

            assertThatThrownBy(() -> ReplayHeaders.requireComplete(headers))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("UUID");
        }

        @Test
        @DisplayName("ledger-owner 가 알 수 없는 서비스면 거부한다")
        void rejectsUnknownOwner() {
            Map<String, String> headers = valid();
            headers.put(ReplayHeaders.LEDGER_OWNER, "user");   // DLQ 원장을 갖지 않는 서비스

            assertThatThrownBy(() -> ReplayHeaders.requireComplete(headers))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("알 수 없는 서비스");
        }

        @Test
        @DisplayName("ledger-owner 는 enum name 이 아니라 prefix 다")
        void ownerUsesPrefixNotEnumName() {
            Map<String, String> headers = valid();
            headers.put(ReplayHeaders.LEDGER_OWNER, "ORDER");

            assertThatThrownBy(() -> ReplayHeaders.requireComplete(headers))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("root-id 가 정수가 아니거나 양수가 아니면 거부한다")
        void rejectsBadRootId() {
            for (String bad : new String[]{"abc", "0", "-1", "1.5"}) {
                Map<String, String> headers = valid();
                headers.put(ReplayHeaders.ROOT_ID, bad);
                assertThatThrownBy(() -> ReplayHeaders.requireComplete(headers))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining(ReplayHeaders.ROOT_ID);
            }
        }
    }
}
