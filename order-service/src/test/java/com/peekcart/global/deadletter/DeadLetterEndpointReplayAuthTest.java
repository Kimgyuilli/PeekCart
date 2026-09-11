package com.peekcart.global.deadletter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code action=replay} 의 권한·감사 주체 계약 (구현 ④-c-2b-4a · diff 리뷰 1R #3).
 *
 * <p>다른 전이는 원장 상태만 바꾸지만 replay 는 <b>업무 토픽에 실제 메시지를 다시 싣는다</b>.
 * 공통 보안 체인은 {@code anyRequest().authenticated()} 뿐이라 ROLE_USER 도 통과하므로
 * 진입점이 직접 ADMIN 을 요구한다.
 */
class DeadLetterEndpointReplayAuthTest {

    private final DeadLetterRecordJpaRepository repository = mock(DeadLetterRecordJpaRepository.class);
    private final DeadLetterTransitionService transitionService = mock(DeadLetterTransitionService.class);
    private final DeadLetterReplayService replayService = mock(DeadLetterReplayService.class);
    private final DeadLetterEndpoint endpoint =
            new DeadLetterEndpoint(repository, transitionService, replayService);

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String principal, String role) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + role))));
    }

    @Test
    @DisplayName("ROLE_USER 는 replay 를 개시할 수 없다 — 서비스까지 도달하지 않는다")
    void userRoleIsRejected() {
        authenticateAs("7", "USER");

        // **본문이 아니라 예외로 거부한다** — 200 + error 문자열이면 운영 자동화가 성공으로 오판하고
        // Security 의 접근 거부 감사에도 잡히지 않는다.
        assertThatThrownBy(() -> endpoint.transition(1L, "replay", "someone", null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("ADMIN 권한");
        // **거부는 진입점에서 끝나야 한다** — 서비스가 불린 뒤 결과만 감추면 적격성 조회·잠금이 이미 돈다.
        verify(replayService, never()).replay(any(), any());
    }

    @Test
    @DisplayName("인증이 없으면 거부한다")
    void anonymousIsRejected() {
        assertThatThrownBy(() -> endpoint.transition(1L, "replay", "someone", null))
                .isInstanceOf(AccessDeniedException.class);
        verify(replayService, never()).replay(any(), any());
    }

    @Test
    @DisplayName("ADMIN 은 개시할 수 있고, 감사 주체는 인증 주체가 앞에 온다 — 요청값만으로 위조할 수 없다")
    void adminRoleDelegatesWithAuthenticatedActor() {
        authenticateAs("42", "ADMIN");
        when(replayService.replay(eq(1L), any()))
                .thenReturn(Optional.of(new DeadLetterReplayService.Result(1L, 1L, "a-1", List.of())));

        Map<String, Object> response = endpoint.transition(1L, "replay", "야간 당직", null);

        assertThat(response.get("accepted")).isEqualTo(true);
        // 요청값은 메모로만 남고, 주체는 인증에서 온다.
        verify(replayService).replay(1L, "42(야간 당직)");
    }

    @Test
    @DisplayName("actor 가 비면 replay 분기에 닿기 전에 기존 가드가 막는다 — 감사 주체 없는 개시는 없다")
    void blankActorIsRejectedByExistingGuard() {
        authenticateAs("42", "ADMIN");

        Map<String, Object> response = endpoint.transition(1L, "replay", "  ", null);

        assertThat(response.get("error")).asString().contains("actor 는 필수");
        verify(replayService, never()).replay(any(), any());
    }
}
