package com.peekcart.global.deadletter;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * DLQ 원장 조회·종결 표면 (계획 ④-c-2a P10·P12).
 *
 * <p><b>왜 조회가 필요한가</b>: Slack 알림은 best-effort 라 놓칠 수 있고 일부 서비스에서는 no-op 이다.
 * 메트릭 계약(④-d 부모 P11)이 서기 전까지 "지금 미결이 몇 건이고 가장 오래된 건 얼마나 됐나" 를
 * 물어볼 수단이 있어야 한다 — 없으면 원장이 있어도 <b>운영이 관측 불능</b>이다.
 *
 * <p><b>왜 종결까지 여기서 하는가</b>: runbook 이 {@code UPDATE dead_letter_records SET status=...} 를
 * 지시하면 {@link DeadLetterRecord#discard} 의 <b>"사유 필수" 가드와 상태 전이 규칙이 통째로 우회</b>된다.
 * 그러면 그 가드는 코드에만 있고 운영에는 없는 장식이 되고, 리허설도 "SQL 이 돌았다" 만 증명하는
 * false-green 이 된다. 종결은 <b>도메인 메서드를 거치는 이 진입점</b>으로만 한다.
 *
 * <p>노출은 {@code management.endpoints.web.exposure.include} 의 {@code deadletter} 로 켜지며,
 * {@code ActuatorSecurityConfig} 의 permitAll 목록에 <b>없으므로</b> 인증 뒤에 있다.
 *
 * <p><b>종결은 incident 단위다</b>(④-c-2b-1 P5) — 자식 id 로 들어와도 canonical root 로 정규화하고
 * root 와 활성 자식을 함께 전이한다. 자식만 닫으면 미결을 종결로 위장한다.
 *
 * <p><b>재발행 개시({@code action=replay})도 여기 하나뿐이다</b>(④-c-2b-4a P21 · N14). 진입점이 둘이 되면
 * 적격성·fence·claim 을 우회하는 경로가 생긴다 — P25 의 진입점 단일성 lint 가 그것을 정적으로 막는다.
 * kill-switch {@code app.dead-letter.replay.enabled} 의 <b>기본값은 false</b> 다.
 *
 * <p><b>{@code replay} 만 ADMIN 을 요구한다</b> — 나머지 전이는 원장 상태만 바꾸지만 replay 는
 * <b>업무 토픽에 실제 메시지를 다시 싣는다</b>. 감사 주체(actor)도 요청값이 아니라 인증 주체에서 얻는다.
 */
@Component
@Endpoint(id = "deadletter")
@RequiredArgsConstructor
public class DeadLetterEndpoint {

    private final DeadLetterRecordJpaRepository repository;
    private final DeadLetterTransitionService transitionService;
    private final DeadLetterReplayService replayService;

    /**
     * backlog 요약. {@code GET /actuator/deadletter}
     *
     * <p>{@code unresolved} 는 <b>incident(root) 수</b>다 — 재발행 재실패로 늘어난 자식은 세지 않는다.
     * {@code publication} 은 그 미결 incident 의 <b>발행 축 분포</b>이며 네 값의 합은 {@code unresolved} 와
     * 같다({@code NOT_REQUESTED} = 아직 replay 를 요청하지 않은 건) — 다섯 번의 집계 조회를
     * <b>하나의 read-only 트랜잭션</b>에서 수행해 그 합 불변식을 지킨다. 조회마다 커밋 경계가 갈리면
     * 그 사이의 전이가 같은 행을 두 번 세거나 한 번도 세지 않아 합이 어긋난다. <b>{@code PUBLISHED} 도 미결에
     * 포함된다</b> — 발행 성공은 사건 해소가 아니다(ADR-0020 §D6-2).
     */
    @ReadOperation
    @Transactional(readOnly = true)
    public Map<String, Object> backlog() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("unresolved", repository.countUnresolved());

        repository.findOldestUnresolvedOccurredAt().ifPresentOrElse(oldest -> {
            result.put("oldestOccurredAt", oldest.toString());
            result.put("oldestAgeSeconds", Duration.between(oldest, LocalDateTime.now()).toSeconds());
        }, () -> {
            result.put("oldestOccurredAt", null);
            result.put("oldestAgeSeconds", 0L);
        });

        Map<String, Object> publication = new LinkedHashMap<>();
        // NULL 은 ADR-0020 §D6-1 표의 정식 상태("요청 없음")다. 빼면 미결이 있는데도 분포가 전부 0 이 된다.
        publication.put("NOT_REQUESTED", repository.countUnresolvedWithoutPublication());
        for (PublicationStatus status : PublicationStatus.values()) {
            publication.put(status.name(), repository.countUnresolvedByPublicationStatus(status));
        }
        result.put("publication", publication);

        return result;
    }

    /**
     * incident 1건을 전이한다. {@code POST /actuator/deadletter/{id}}
     *
     * <p>본문: {@code {"action":"acknowledge","actor":"..."}} ·
     * {@code {"action":"resolve","actor":"...","reason":"..."}} ·
     * {@code {"action":"discard","actor":"...","reason":"..."}} ·
     * {@code {"action":"replay","actor":"..."}}
     *
     * <p>본문은 <b>flat 파라미터</b>다 — actuator {@code @WriteOperation} 은 중첩 객체를 받지 않는다.
     *
     * <p>{@code resolve}/{@code discard} 는 사유가 없으면 거부된다 — 근거 없이 닫힌 원장은 "해결됨" 과
     * 구분되지 않는다. {@code resolve} 의 사유는 <b>무엇을 보고 해소를 확인했는지</b>여야 한다.
     * 이미 전이된 건은 예외가 아니라 {@code changed=false} 로 응답한다(멱등).
     *
     * <p>대상 id 가 자식이면 <b>root 로 정규화</b>되며, 응답의 {@code rootId} 가 실제 전이 대상이다.
     */
    @WriteOperation
    public Map<String, Object> transition(@Selector Long id, String action, String actor, String reason) {
        if (actor == null || actor.isBlank()) {
            return Map.of("error", "actor 는 필수입니다 — 누가 종결했는지 남지 않으면 감사가 불가능합니다");
        }

        if ("replay".equals(action)) {
            return replay(id, actor);
        }

        Optional<DeadLetterTransitionService.Result> outcome;
        try {
            outcome = switch (action == null ? "" : action) {
                case "acknowledge" -> transitionService.acknowledge(id, actor);
                case "resolve" -> transitionService.resolve(id, actor, reason);
                case "discard" -> transitionService.discard(id, actor, reason);
                default -> throw new IllegalArgumentException(
                        "action 은 acknowledge, resolve, discard, replay 중 하나여야 합니다 (받은 값: " + action + ")");
            };
        } catch (IllegalArgumentException e) {
            return Map.of("error", e.getMessage());
        }

        if (outcome.isEmpty()) {
            return Map.of("error", "원장에 id=" + id + " 가 없습니다");
        }

        DeadLetterTransitionService.Result result = outcome.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("rootId", result.rootId());
        response.put("status", result.status());
        response.put("changed", result.changed());
        response.put("affectedChildren", result.affectedChildren());
        // changed=false 의 두 경우를 구분한다 — 멱등 no-op(null) vs I-1 거부(사유 존재).
        response.put("rejectedReason", result.rejectedReason());
        return response;
    }

    /**
     * 재발행을 개시한다 (④-c-2b-4a P21).
     *
     * <p>거부는 예외가 아니라 <b>사유 전량</b>으로 응답한다 — 6 금지축은 서로 독립이므로 첫 번째에서
     * 멈추면 운영자가 한 번에 한 축만 보고 고치기를 반복한다.
     */
    private Map<String, Object> replay(Long id, String actor) {
        // **replay 는 ADMIN 전용이다** (diff 리뷰 1R #3). 다른 전이와 달리 이 요청은 **업무 토픽에 실제
        // 메시지를 다시 싣는다** — 공통 체인의 `authenticated()` 만으로는 ROLE_USER 도 통과한다.
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!hasAdminRole(authentication)) {
            return Map.of("error", "replay 는 ADMIN 권한이 필요합니다");
        }

        // **actor 를 요청값 그대로 믿지 않는다.** 인증 주체를 앞에 붙여 감사 주체를 위조할 수 없게 한다 —
        // 요청값은 사람이 읽을 메모로만 남는다. (공란은 이 메서드 앞머리의 기존 가드가 이미 막는다.)
        String auditActor = authentication.getName() + "(" + actor + ")";

        Optional<DeadLetterReplayService.Result> outcome = replayService.replay(id, auditActor);
        if (outcome.isEmpty()) {
            return Map.of("error", "원장에 id=" + id + " 가 없습니다");
        }

        DeadLetterReplayService.Result result = outcome.get();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", id);
        response.put("rootId", result.rootId());
        response.put("accepted", result.accepted());
        response.put("targetId", result.targetId());
        response.put("attemptId", result.attemptId());
        response.put("rejections", result.rejections());
        return response;
    }

    private boolean hasAdminRole(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(granted -> "ROLE_ADMIN".equals(granted.getAuthority()));
    }
}
