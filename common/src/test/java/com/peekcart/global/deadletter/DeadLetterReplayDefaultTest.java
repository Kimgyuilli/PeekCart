package com.peekcart.global.deadletter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * replay kill-switch 의 <b>기본값</b> 계약 (구현 ④-c-2b-4a P21 · 계획 리뷰 1R #6).
 *
 * <p><b>왜 별도 테스트인가</b>: 진입점 통합테스트는 {@code @BeforeEach} 에서 값을 명시적으로 세팅하므로
 * <b>기본값을 아무도 관측하지 않는다</b>. 실제로 기본값을 {@code true} 로 뒤집는 변이(M1)에서 그 테스트가
 * 통과했다 — 테스트가 계약이 아니라 자기가 세팅한 상태를 기술하고 있었다.
 *
 * <p>기본값이 {@code false} 여야 하는 이유: 배포 순간 진입점이 열리면 backfill·증적·리허설 이전에
 * replay 가 가능해지고, 그것을 닫는 데 롤링 재기동이 또 든다(Spring 정적 설정).
 */
class DeadLetterReplayDefaultTest {

    @Test
    @DisplayName("아무 설정도 주지 않으면 replay 는 꺼져 있다")
    void replayIsDisabledByDefault() {
        assertThat(new DeadLetterProperties().getReplay().isEnabled())
                .as("기본값이 true 면 배포 즉시 진입점이 열린다")
                .isFalse();
    }
}
