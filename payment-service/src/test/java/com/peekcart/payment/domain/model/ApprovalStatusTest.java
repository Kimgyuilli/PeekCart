package com.peekcart.payment.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ApprovalStatus 전이 전수 검증 (ADR-0023 D2)")
class ApprovalStatusTest {

    /** 허용 전이 정본. 여기 없는 조합은 전부 거부돼야 한다. */
    private static final Map<ApprovalStatus, Set<ApprovalStatus>> ALLOWED = new EnumMap<>(Map.of(
            ApprovalStatus.CLAIMED, Set.of(ApprovalStatus.SUCCEEDED, ApprovalStatus.FAILED,
                    ApprovalStatus.UNRESOLVED, ApprovalStatus.CLAIMED),
            ApprovalStatus.UNRESOLVED, Set.of(ApprovalStatus.SUCCEEDED, ApprovalStatus.FAILED,
                    ApprovalStatus.CLAIMED),
            ApprovalStatus.SUCCEEDED, Set.of(),
            ApprovalStatus.FAILED, Set.of()
    ));

    @ParameterizedTest(name = "{0} 의 전이는 정본과 정확히 일치한다")
    @EnumSource(ApprovalStatus.class)
    void transitions_matchContract(ApprovalStatus from) {
        for (ApprovalStatus to : ApprovalStatus.values()) {
            assertThat(from.canTransitionTo(to))
                    .as("%s → %s", from, to)
                    .isEqualTo(ALLOWED.get(from).contains(to));
        }
    }

    @Test
    @DisplayName("UNRESOLVED 는 종결이 아니다 — 나가는 전이가 없으면 '미결로 남기지 않는다'가 깨진다")
    void unresolved_isNotTerminal() {
        assertThat(ApprovalStatus.UNRESOLVED.isTerminal()).isFalse();
        assertThat(ALLOWED.get(ApprovalStatus.UNRESOLVED)).isNotEmpty();
    }

    @Test
    @DisplayName("CLAIMED 도 종결이 아니다 — reconciliation 이 회수해야 하는 상태다")
    void claimed_isNotTerminal() {
        assertThat(ApprovalStatus.CLAIMED.isTerminal()).isFalse();
    }

    @Test
    @DisplayName("SUCCEEDED / FAILED 만 종결이다")
    void onlyTwoTerminalStates() {
        assertThat(ApprovalStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(ApprovalStatus.FAILED.isTerminal()).isTrue();
    }
}
