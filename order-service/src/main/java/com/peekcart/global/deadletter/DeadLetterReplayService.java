package com.peekcart.global.deadletter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.global.kafka.LedgerOwner;
import com.peekcart.global.kafka.PayloadDigest;
import com.peekcart.global.kafka.ReplayHeaders;
import com.peekcart.global.outbox.OutboxEvent;
import com.peekcart.global.outbox.OutboxEventJpaRepository;
import com.peekcart.global.replay.OriginalRecordReader;
import com.peekcart.global.replay.ReplayDeadlines;
import com.peekcart.global.replay.ReplayPolicy;
import com.peekcart.global.replay.ReplayPolicyRegistry;
import com.peekcart.global.retention.IdempotencyRetentionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * DLQ replay <b>개시</b> — 적격성(P19) · fence(P20) · claim(P21) (ADR-0020 §D5·§D6-4·§D8).
 *
 * <h2>한 트랜잭션 안의 순서가 계약이다</h2>
 * <ol>
 *   <li><b>canonical root 를 {@code FOR UPDATE}</b> 로 잠근다 — 종결·재개방·purge·reconciler 가 전부
 *       같은 순서로 진입하므로 순환이 없다</li>
 *   <li>적격성 6축(§D5-2)을 <b>독립 조건으로</b> 평가해 <b>사유를 전부 모아</b> 반환한다.
 *       첫 번째에서 멈추면 운영자가 한 번에 한 축만 본다</li>
 *   <li>판정을 <b>allow·deny 양쪽 다</b> root 에 감사 기록한다 — deny 는 claim 에 도달하지 않으므로
 *       claim 에만 기록하면 거부 이력이 남지 않는다</li>
 *   <li><b>조건부 claim</b> → 영향 행 0 이면 즉시 거부(아직 outbox 를 만들지 않아 orphan 이 없다)</li>
 *   <li>root 앵커 기록 → outbox INSERT → <b>target row</b> 에 {@code outbox_event_id} 연결</li>
 * </ol>
 *
 * <p><b>{@code outbox_event_id} 는 target row 에 쓴다</b> — root 에 쓰면 reconciler 가 {@code REQUESTED}
 * 인 자식의 {@code null} 연결을 읽고 영원히 건너뛴다. 두 축은 같은 행에 있어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterReplayService {

    private final DeadLetterRecordJpaRepository repository;
    private final OutboxEventJpaRepository outboxEventJpaRepository;
    private final OriginalRecordReader originalRecordReader;
    private final DeadLetterProperties properties;
    private final IdempotencyRetentionProperties retentionProperties;
    private final LedgerOwner ledgerOwner;
    private final ObjectMapper objectMapper;
    private final Optional<ReplayPreconditionPort> precondition;

    /**
     * @param rootId     정규화된 incident root
     * @param targetId   발행 축을 선점한 행. 거부면 {@code null}
     * @param attemptId  이 시도의 UUID. 거부면 {@code null}
     * @param rejections 거부 사유 전량. 비어 있으면 승인
     */
    public record Result(Long rootId, Long targetId, String attemptId, List<String> rejections) {

        public boolean accepted() {
            return rejections.isEmpty();
        }
    }

    /**
     * @return 원장에 {@code id} 가 없으면 {@link Optional#empty()}
     */
    @Transactional
    public Optional<Result> replay(Long id, String actor) {
        Optional<Long> rootIdOpt = repository.findRootIdOf(id);
        if (rootIdOpt.isEmpty()) {
            return Optional.empty();
        }
        Long rootId = rootIdOpt.get();

        Optional<DeadLetterRecord> rootOpt = repository.findByIdForUpdate(rootId);
        if (rootOpt.isEmpty()) {
            log.error("[DLQ-REPLAY] 자식이 가리키는 root 가 없다 — id={}, rootId={}", id, rootId);
            return Optional.empty();
        }
        DeadLetterRecord root = rootOpt.get();

        if (!properties.getReplay().isEnabled()) {
            // kill-switch. 감사 기록도 남기지 않는다 — 판정 자체를 하지 않았다.
            return Optional.of(new Result(rootId, null, null,
                    List.of("replay 진입점이 꺼져 있다 — app.dead-letter.replay.enabled=false")));
        }

        Eligibility eligibility = evaluate(root);

        // allow·deny 양쪽 다 root 잠금 안에서 기록한다(계획 리뷰 2R #9 — 순서를 안 박으면
        // 동시 deny 가 allow 앵커를 덮어 root=deny/자식=allow 조합이 남는다).
        repository.stampReplayPolicy(rootId, eligibility.audit());

        if (!eligibility.reasons().isEmpty()) {
            log.info("[DLQ-REPLAY] 거부 — rootId={}, actor={}, 사유={}", rootId, actor, eligibility.reasons());
            return Optional.of(new Result(rootId, null, null, eligibility.reasons()));
        }

        DeadLetterRecord target = latestActiveChild(rootId).orElse(root);

        if (repository.claimPublication(target.getId()) == 0) {
            return Optional.of(new Result(rootId, null, null, List.of(
                    "발행 축을 선점할 수 없다 — 동시 요청이 이미 선점했거나(REQUESTED) 사건이 종결됐다")));
        }

        String attemptId = UUID.randomUUID().toString();
        String targetGroup = root.getFailedConsumerGroup();
        OriginalRecordReader.Original original = eligibility.original();
        String digest = PayloadDigest.sha256Hex(original.value());

        repository.stampReplayAnchor(rootId, attemptId, targetGroup, digest,
                eligibility.audit(), eligibility.deadline());

        OutboxEvent event = OutboxEvent.replay(
                root.getId(),
                root.getOriginTopic(), root.getOriginPartition(),
                original.key(), original.value(),
                original.timestamp(),
                eligibility.originalEventId(), replayHeadersJson(attemptId, targetGroup, rootId),
                rootId, targetGroup,
                null, null);
        OutboxEvent saved = outboxEventJpaRepository.save(event);
        repository.linkOutboxEvent(target.getId(), saved.getId());

        log.info("[DLQ-REPLAY] 개시 — rootId={}, targetId={}, attemptId={}, group={}, actor={}",
                rootId, target.getId(), attemptId, targetGroup, actor);
        return Optional.of(new Result(rootId, target.getId(), attemptId, List.of()));
    }

    private Optional<DeadLetterRecord> latestActiveChild(Long rootId) {
        List<DeadLetterRecord> children =
                repository.findLatestActiveChildForUpdate(rootId, PageRequest.of(0, 1));
        return children.isEmpty() ? Optional.empty() : Optional.of(children.get(0));
    }

    // --- 적격성 ---

    private record Eligibility(List<String> reasons, OriginalRecordReader.Original original,
                               String originalEventId, LocalDateTime deadline, String audit) {
    }

    /**
     * ADR §D5-2 의 6 금지축 + §D5-3 안전창 + §D8-3 fence 를 <b>독립 조건으로</b> 평가한다.
     * 앞 축이 걸려도 뒤 축을 계속 평가한다 — 단, 원본 레코드를 읽지 못하면 그것에 의존하는
     * 축(fence·eventType·사전조건)은 평가할 수 없으므로 건너뛰고 그 사실을 사유에 남긴다.
     */
    private Eligibility evaluate(DeadLetterRecord root) {
        List<String> reasons = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        // 축 1 — eventId 부재: 실패하지 않은 group 의 멱등 억제가 불가능하다.
        if (root.getEventId() == null || root.getEventId().isBlank()) {
            reasons.add("[축1] event_id 가 없다 — 다른 group 의 멱등 억제가 불가능하다");
        }
        // 축 2 — 어느 group 이 실패했는지 모른다.
        if (DlqOrigin.UNKNOWN_CONSUMER_GROUP.equals(root.getFailedConsumerGroup())) {
            reasons.add("[축2] failed_consumer_group 이 " + DlqOrigin.UNKNOWN_CONSUMER_GROUP + " 다");
        }
        // 축 3 — 좌표가 .dlq 자신이라 그대로 replay 하면 .dlq 내용이 원본 토픽에 주입된다.
        if (root.getOriginKind() == DlqOriginKind.DLQ_ORIGIN) {
            reasons.add("[축3] origin_kind 가 DLQ_ORIGIN 이다 — 원본 좌표를 알 수 없다");
        }
        // 축 6 — 안전창의 기준시각을 세울 수 없다.
        LocalDateTime deadline = null;
        if (root.getOriginalTimestamp() == null) {
            reasons.add("[축6] original_timestamp 가 없다 — 멱등 안전창의 기준시각이 없다");
        } else {
            ReplayDeadlines.Result computed = ReplayDeadlines.compute(
                    root.getOriginalTimestamp(), now,
                    retentionProperties.getClockSkewBudget(),
                    retentionProperties.getFloor().getDlqReplayWindow());
            if (computed.rejected()) {
                reasons.add("[축6] " + computed.rejection());
            } else {
                deadline = root.getReplayDeadline() != null ? root.getReplayDeadline() : computed.deadline();
                if (ReplayDeadlines.expired(deadline, now)) {
                    reasons.add("[안전창] 멱등 안전창이 만료됐다 — deadline=" + deadline);
                }
            }
        }
        // 축 4 — 좌표 무효: 세대 불일치.
        int declaredGeneration = properties.generationOf(root.getOriginTopic());
        if (declaredGeneration != root.getTopicGeneration()) {
            reasons.add(String.format("[축4] topic_generation 불일치 — 원장=%d, 현재 선언=%d",
                    root.getTopicGeneration(), declaredGeneration));
        }

        // 축 5 — 정책. default-deny.
        ReplayPolicy policy = ReplayPolicyRegistry.find(ledgerOwner.service(), root.getOriginTopic());
        if (policy == null) {
            reasons.add("[축5] 정책 미등록 — " + ledgerOwner.prefix() + "/" + root.getOriginTopic()
                    + " (default-deny)");
        } else if (!policy.allowed()) {
            reasons.add("[축5] 정책이 금지한다 — " + policy.id());
        }

        // 원본 레코드 — 축 4(좌표 유효성)의 나머지이자 fence(§D8-3)의 입력.
        OriginalRecordReader.Original original = null;
        String originalEventId = null;
        if (root.getOriginKind() == DlqOriginKind.RESOLVED_ORIGIN) {
            OriginalRecordReader.Result read = originalRecordReader.read(
                    root.getOriginTopic(), root.getOriginPartition(), root.getOriginOffset());
            if (read.rejected()) {
                reasons.add("[축4] " + read.rejection());
            } else {
                original = read.record();
                originalEventId = fence(root, original, policy, reasons);
            }
        }

        return new Eligibility(List.copyOf(reasons), original, originalEventId, deadline,
                audit(policy, reasons.isEmpty()));
    }

    /**
     * D8 fence — 발행 직전 최종 검사 (§D8-3). 원본과 어긋나면 outbox 행을 만들지 않는다.
     *
     * @return 원본 payload 안의 eventId
     */
    private String fence(DeadLetterRecord root, OriginalRecordReader.Original original,
                         ReplayPolicy policy, List<String> reasons) {
        if (original.partition() != root.getOriginPartition()) {
            reasons.add(String.format("[fence] 파티션이 원장과 다르다 — 원장=%d, 원본=%d",
                    root.getOriginPartition(), original.partition()));
        }

        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(original.value());
        } catch (Exception e) {
            reasons.add("[fence] 원본 payload 를 파싱하지 못했다: " + e.getMessage());
            return null;
        }

        String eventType = envelope.path("eventType").asText(null);
        if (!ReplayPolicyRegistry.eventTypeMatchesTopic(eventType, root.getOriginTopic())) {
            reasons.add("[축5] 봉투의 eventType 이 목적지 토픽과 다르다 — eventType=" + eventType
                    + ", topic=" + root.getOriginTopic());
        }

        String originalEventId = envelope.path("eventId").asText(null);
        if (originalEventId == null || !originalEventId.equals(root.getEventId())) {
            reasons.add("[fence] 원본 payload 의 eventId 가 원장과 다르다 — 원장=" + root.getEventId()
                    + ", 원본=" + originalEventId);
        }

        if (policy != null && policy.preconditionRequired()) {
            if (precondition.isEmpty()) {
                // fail-closed: 사전조건이 필요한 토픽인데 판정기가 없다 = 배선 누락이다.
                reasons.add("[축5] 사전조건 판정기가 배선되지 않았다 — " + policy.id());
            } else {
                String rejected = precondition.get().reject(root.getOriginTopic(), original.value());
                if (rejected != null) {
                    reasons.add("[축5] 사전조건 불충족 — " + rejected);
                }
            }
        }
        return originalEventId;
    }

    /** 원장 {@code replay_policy} 에 남길 감사 문자열 — {@code 식별자:버전:판정}. */
    private String audit(ReplayPolicy policy, boolean allowed) {
        String id = policy == null ? "UNREGISTERED" : policy.id();
        return id + ":" + ReplayPolicyRegistry.VERSION + ":" + (allowed ? "ALLOW" : "DENY");
    }

    private String replayHeadersJson(String attemptId, String targetGroup, Long rootId) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(ReplayHeaders.ATTEMPT_ID, attemptId);
        headers.put(ReplayHeaders.LEDGER_OWNER, ledgerOwner.prefix());
        headers.put(ReplayHeaders.TARGET_GROUP, targetGroup);
        headers.put(ReplayHeaders.ROOT_ID, String.valueOf(rootId));
        ReplayHeaders.requireComplete(headers);
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (Exception e) {
            throw new IllegalStateException("replay 헤더 직렬화 실패", e);
        }
    }
}
