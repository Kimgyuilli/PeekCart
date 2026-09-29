package com.peekcart.global.deadletter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqPayloads;
import com.peekcart.global.kafka.LedgerOwner;
import com.peekcart.global.kafka.PayloadDigest;
import com.peekcart.global.port.SlackPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;
import java.util.Optional;

/**
 * DLQ 원장 적재 (계획 ④-c-2a P6·P7·P9).
 *
 * <p><b>적재는 멱등하다.</b> {@code INSERT IGNORE} 가 신규 1 / 중복 0 을 돌려주며, 중복이면
 * 시도 횟수만 올린다. 유니크 위반을 catch 하지 않는 이유는 ④-c-1a 선례 — JPA 에서는 flush 시점과
 * rollback-only 때문에 "충돌 잡아서 no-op" 이 성립하지 않는다.
 *
 * <p><b>알림은 best-effort 다.</b> DB commit 과 Slack 호출은 한 트랜잭션이 아니므로 commit 후
 * 사망하면 알림이 0회다. "정확히 1회" 도 at-least-once 도 주장하지 않는다 — <b>내구적 신호는
 * 원장 행 자체</b>이고, 그게 이 기능의 존재 이유다(ADR-0018 D6 도 Slack 을 보조 신호로 규정).
 * 그래서 Slack 실패가 적재를 실패시키지 않는다.
 *
 * <p><b>민감정보 정책</b>(P11):
 * <ul>
 *   <li><b>임의 헤더는 저장하지 않는다.</b> {@code DlqOrigin} 은 표준 {@code DLT_*} 와
 *       {@link com.peekcart.global.kafka.ReplayHeaders} allowlist 4종<b>만</b> 담으므로
 *       {@code X-User-Id} 등 그 밖의 application 헤더는 애초에 원장에 들어오지 않는다 —
 *       제외 목록을 관리할 필요가 없도록 <b>읽을 키를 명시</b>하는 구조로 막았다.
 *       <p>replay 상관 4종은 ④-c-2b-3a 가 더했다. <b>application 헤더지만 저장 대상이다</b> —
 *       원장 앵커와 대조해 재실패를 원래 사건에 잇는 데 쓴다(ADR-0021 §D1). 대조는 ④-c-2b-3b 소관이고,
 *       이 단계에서는 판독만 한다.</li>
 *   <li><b>payload 는 상한까지만</b> 저장하고 초과분은 잘라 {@code payloadTruncated} 로 표시한다.
 *       진단용이며 replay 원본이 아니다 — replay 는 원본 토픽 좌표에서 읽는다(④-c-2b).</li>
 *   <li><b>Slack 에는 식별자와 runbook 링크만</b> 보낸다. 채널은 원장보다 접근 범위가 넓고
 *       본문에는 주문/사용자 정보가 섞일 수 있다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@EnableConfigurationProperties(DeadLetterProperties.class)
public class DeadLetterRecorder {

    private final DeadLetterRecordJpaRepository repository;
    private final DeadLetterProperties properties;
    private final SlackPort slackPort;
    private final ObjectMapper objectMapper;
    private final LedgerOwner ledgerOwner;
    private final DeadLetterMetrics metrics;

    /**
     * DLQ 레코드 1건을 원장에 적재하고, replay 재실패면 원래 incident 에 잇는다 (④-c-2b-3b P15).
     *
     * <p><b>반환값은 "신규 행이 생겼는가" 이지 "알림을 보냈는가" 가 아니다</b>(P15-g-2).
     * 상관에 성공한 자식은 신규 적재({@code true})지만 backlog 가 늘지 않았으므로
     * <b>신규 미결 알림을 보내지 않는다</b>.
     *
     * @return 신규 적재면 true, 이미 있으면(중복 유입) false
     */
    @Transactional
    public boolean record(DlqOrigin origin) {
        int generation = properties.generationOf(origin.originTopic());
        DlqPayloads.Truncation payload =
                DlqPayloads.truncate(origin.payload(), properties.getPayload().getMaxLength());
        String eventId = DlqPayloads.extractEventId(objectMapper, origin.payload());

        DeadLetterRecord candidate = DeadLetterRecord.open(
                properties.getClusterId(), generation, origin, eventId, payload.value(), payload.truncated());

        // --- 단계 1~3: 로케이터 → root 잠금 → 유일 대조 (P15-a). INSERT 보다 **먼저** 한다 ---
        Correlation correlation = correlate(origin, candidate, eventId);

        int inserted = repository.insertIfAbsent(
                candidate.getClusterId(), candidate.getTopicGeneration(),
                candidate.getOriginTopic(), candidate.getOriginPartition(), candidate.getOriginOffset(),
                candidate.getFailedConsumerGroup(), candidate.getOriginKind().name(),
                candidate.getEventId(), candidate.getOriginalKey(), candidate.getOriginalTimestamp(),
                candidate.getPayload(), candidate.isPayloadTruncated(),
                candidate.getExceptionType(), candidate.getExceptionMessage(),
                candidate.getOccurredAt());

        if (inserted == 0) {
            // 중복 유입: 대조는 이미 계산됐지만 **계측·알림·재개방을 전부 생략**한다 (P15-e).
            // 첫 적재 때 상관 판정이 끝났고, 재전달마다 재개방하면 운영자가 닫은 root 를
            // broker 재전달만으로 다시 여는 경로가 생긴다.
            repository.incrementAttempt(
                    candidate.getClusterId(), candidate.getTopicGeneration(),
                    candidate.getOriginTopic(), candidate.getOriginPartition(),
                    candidate.getOriginOffset(), candidate.getFailedConsumerGroup());
            log.info("DLQ 원장 중복 유입 — topic={}, partition={}, offset={}, group={}",
                    origin.originTopic(), origin.originPartition(), origin.originOffset(),
                    origin.failedConsumerGroup());
            return false;
        }

        // 방금 넣은 행. INSERT IGNORE 가 건너뛴 경우 LAST_INSERT_ID() 가 **직전 성공 INSERT 의 값**을
        // 돌려줘 남의 행을 지목하므로 좌표로 다시 찾는다 (④-c-2b-1 P3).
        DeadLetterRecord child = repository
                .findByClusterIdAndTopicGenerationAndOriginTopicAndOriginPartitionAndOriginOffsetAndFailedConsumerGroup(
                        candidate.getClusterId(), candidate.getTopicGeneration(),
                        candidate.getOriginTopic(), candidate.getOriginPartition(),
                        candidate.getOriginOffset(), candidate.getFailedConsumerGroup())
                .orElseThrow(() -> new IllegalStateException(
                        "방금 적재한 DLQ 원장 행을 좌표로 찾지 못했다 — topic=" + candidate.getOriginTopic()
                                + ", partition=" + candidate.getOriginPartition()
                                + ", offset=" + candidate.getOriginOffset()));

        if (!correlation.correlated()) {
            child.assignSelfRoot();
            metrics.recordCorrelation(false, correlation.reason());
            log.error("DLQ 원장 신규 적재 — topic={}, partition={}, offset={}, group={}, kind={}, exception={}",
                    origin.originTopic(), origin.originPartition(), origin.originOffset(),
                    origin.failedConsumerGroup(), origin.originKind(), origin.exceptionType());
            afterCommit(() -> notifyNewIncident(origin, generation));
            return true;
        }

        // --- 단계 5~6: root 를 **current read** 로 다시 읽고 잇는다 (P15-a, 5R #3) ---
        // 단계 4 의 insertIfAbsent 가 clearAutomatically 로 컨텍스트를 비웠다. 일반 findById 면
        // 단계 1 이 연 REPEATABLE READ 스냅샷을 다시 읽어, 그 사이 닫힌 root 를 OPEN 으로 보고
        // 재개방을 건너뛴다. 행 잠금은 이미 우리 것이라 이 재조회는 대기하지 않는다.
        DeadLetterRecord root = repository.findByIdForUpdate(correlation.rootId())
                .orElseThrow(() -> new IllegalStateException(
                        "잠근 root 가 사라졌다 — rootId=" + correlation.rootId()));

        child.linkToRoot(root.getId());
        metrics.recordCorrelation(true, DeadLetterMetrics.CorrelationReason.NONE);

        boolean reopened = root.reopen(
                "replay 재실패 상관 — attemptId=" + origin.replayAttemptId()
                        + ", childId=" + child.getId());

        log.error("DLQ 재실패 상관 — rootId={}, childId={}, attemptId={}, group={}, 재개방={}",
                root.getId(), child.getId(), origin.replayAttemptId(),
                origin.failedConsumerGroup(), reopened);

        if (reopened) {
            metrics.recordReopened();
            DeadLetterStatus previous = correlation.previousStatus();
            afterCommit(() -> notifyReopened(origin, root.getId(), child.getId(), previous));
        }
        return true;
    }

    /**
     * 잠근 root 와 9축을 대조한다 (P15-b). 하나라도 어긋나면 상관하지 않고 독립 root 로 간다.
     *
     * <p><b>잠금 순서 규약</b>: root 를 가장 먼저 잠근다 — 종결 전파(P5)·purge(P4)와 같은 진입
     * 순서라야 순환이 없다.
     */
    private Correlation correlate(DlqOrigin origin, DeadLetterRecord candidate, String eventId) {
        if (!origin.claimsReplay()) {
            return Correlation.independent(DeadLetterMetrics.CorrelationReason.NO_ATTEMPT_HEADER);
        }

        // 단계 1 — 로케이터. **대조가 아니라 탐색이다**: 못 찾으면 잠금을 호출하지 않는다.
        Optional<Long> rootId = repository.findRootIdByReplayAttemptId(origin.replayAttemptId());
        if (rootId.isEmpty()) {
            return Correlation.independent(DeadLetterMetrics.CorrelationReason.ATTEMPT_NOT_FOUND);
        }

        // 단계 2 — root 잠금. DB 행 잠금은 트랜잭션 끝까지 유지되므로 이후의 컨텍스트 clear 와 무관하다.
        Optional<DeadLetterRecord> locked = repository.findByIdForUpdate(rootId.get());
        if (locked.isEmpty()) {
            return Correlation.independent(DeadLetterMetrics.CorrelationReason.ATTEMPT_NOT_FOUND);
        }
        DeadLetterRecord root = locked.get();

        // 단계 3 — 유일한 대조.
        DeadLetterMetrics.CorrelationReason mismatch = mismatchAxis(origin, candidate, eventId, root);
        if (mismatch != null) {
            return Correlation.independent(mismatch);
        }
        return new Correlation(true, root.getId(), root.statusValue(),
                DeadLetterMetrics.CorrelationReason.NONE);
    }

    /** 9축 중 처음 어긋난 축의 사유. 전부 일치하면 {@code null}. */
    private DeadLetterMetrics.CorrelationReason mismatchAxis(
            DlqOrigin origin, DeadLetterRecord candidate, String eventId, DeadLetterRecord root) {

        // 축 1 — attempt. 잠금 후 여기서만 대조한다 (TOCTOU 창을 닫는 지점, P15-c).
        if (!origin.replayAttemptId().equals(root.getLastReplayAttemptId())) {
            return DeadLetterMetrics.CorrelationReason.ATTEMPT_CHANGED;
        }
        // 축 2 — owner. 원장을 소유한 서비스에서만 상관한다.
        if (!ledgerOwner.owns(origin.replayLedgerOwner())) {
            return DeadLetterMetrics.CorrelationReason.OWNER_MISMATCH;
        }
        // 축 3 — group 3자 대조. 헤더를 빼고 둘만 비교하면 pc-replay-target-group 판독이 죽은 데이터가 된다.
        if (!Objects.equals(origin.failedConsumerGroup(), origin.replayTargetGroup())
                || !Objects.equals(origin.replayTargetGroup(), root.getLastReplayTargetGroup())) {
            return DeadLetterMetrics.CorrelationReason.GROUP_MISMATCH;
        }
        // 축 4 — root-id.
        if (!Objects.equals(origin.replayRootId(), root.getId())) {
            return DeadLetterMetrics.CorrelationReason.ROOT_ID_MISMATCH;
        }
        // 축 5 — topic. destination 대조를 겸한다 (같은 비교다).
        if (!Objects.equals(origin.originTopic(), root.getOriginTopic())) {
            return DeadLetterMetrics.CorrelationReason.TOPIC_MISMATCH;
        }
        // 축 6~8 — fingerprint (eventId · key · timestamp). 전부 null-safe.
        if (!Objects.equals(eventId, root.getEventId())
                || !Objects.equals(candidate.getOriginalKey(), root.getOriginalKey())
                || !Objects.equals(candidate.getOriginalTimestamp(), root.getOriginalTimestamp())) {
            return DeadLetterMetrics.CorrelationReason.FINGERPRINT_MISMATCH;
        }
        // 축 9 — payload digest. 절단 전 전문으로 계산한다 (payload 컬럼은 잘려 있어 대조에 못 쓴다).
        // ADR-0021 §D2 대로 null-safe 다 — 양쪽 null 이면 일치.
        if (!Objects.equals(PayloadDigest.sha256Hex(origin.payload()), root.getLastReplayPayloadDigest())) {
            return DeadLetterMetrics.CorrelationReason.DIGEST_MISMATCH;
        }
        return null;
    }

    /**
     * 상관 판정 결과.
     *
     * @param previousStatus 상관 성공 시 <b>재개방 전</b> root 상태. 알림 문구에 쓴다
     */
    private record Correlation(boolean correlated, Long rootId, DeadLetterStatus previousStatus,
                               DeadLetterMetrics.CorrelationReason reason) {

        static Correlation independent(DeadLetterMetrics.CorrelationReason reason) {
            return new Correlation(false, null, null, reason);
        }
    }

    /**
     * commit 후에 실행한다 (P15-g).
     *
     * <p>기존 코드는 {@code @Transactional} 안에서 Slack 을 호출해 <b>commit 실패 전에 알림이 먼저
     * 나갈 수 있었다</b> — javadoc 이 서술하는 "commit 후" 와 달랐다.
     *
     * <p><b>callback 예외는 격리한다</b> — ④-d-1 3R #2 에서 {@code afterCommit} 예외가 호출자에게
     * 전파돼 이미 커밋된 이벤트를 listener 가 재처리한 전례가 있다.
     */
    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            runIsolated(action);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                runIsolated(action);
            }
        });
    }

    private void runIsolated(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            // 알림 실패가 이미 커밋된 적재를 되돌리면 안 된다 — 원장이 내구적 신호다.
            log.warn("DLQ 원장 알림 발송 실패 (적재는 성공)", e);
        }
    }

    /**
     * 식별자와 runbook 링크만 보낸다. <b>payload·exception 원문은 넣지 않는다</b> —
     * Slack 채널은 원장보다 접근 범위가 넓고, 그 본문에는 개인정보가 섞일 수 있다(P9).
     *
     * <p><b>독립 root 로 적재된 경우에만</b> 보낸다 — 상관된 자식은 backlog 를 늘리지 않으므로
     * "신규 미결 1건" 이 거짓이 된다(P15-g-2).
     */
    private void notifyNewIncident(DlqOrigin origin, int generation) {
        slackPort.send(String.format(
                "[DLQ 원장] 신규 미결 1건 — cluster=%s, gen=%d, topic=%s, partition=%d, offset=%d, group=%s, kind=%s%n"
                        + "조치: docs/runbooks/dlq-recovery.md",
                properties.getClusterId(), generation, origin.originTopic(), origin.originPartition(),
                origin.originOffset(), origin.failedConsumerGroup(), origin.originKind()));
    }

    /**
     * 재개방 전용 문구 (P15-h, ADR-0020 §D6-2b I-2).
     *
     * <p><b>사람이 닫은 것을 시스템이 되돌리는 유일한 경로</b>라 신규 적재와 구분되어야 한다 —
     * 문구가 하나뿐이면 운영자가 그 차이를 볼 수 없다. 직전 상태를 함께 실어 무엇이 되돌려졌는지 남긴다.
     */
    private void notifyReopened(DlqOrigin origin, Long rootId, Long childId, DeadLetterStatus previous) {
        slackPort.send(String.format(
                "[DLQ 원장] 종결 incident 재개방 — cluster=%s, rootId=%d, childId=%d, 직전상태=%s%n"
                        + "replay attemptId=%s, group=%s, topic=%s%n"
                        + "조치: docs/runbooks/dlq-recovery.md",
                properties.getClusterId(), rootId, childId, previous,
                origin.replayAttemptId(), origin.failedConsumerGroup(), origin.originTopic()));
    }
}
