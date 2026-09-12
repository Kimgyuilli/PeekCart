package com.peekcart.global.deadletter;

import com.peekcart.global.outbox.OutboxEventJpaRepository;
import com.peekcart.global.outbox.OutboxEventStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * DLQ 원장 발행 축의 <b>건별 종착 처리</b> (ADR-0020 §D6-4 · 구현 ④-c-2b-4a P21).
 *
 * <p><b>왜 스캐너에서 분리했는가</b>(계획 리뷰 2R #5 · 3R #8): 종착은 원장 행을 잠그고 바꾼다.
 * 스캔 배치 전체를 한 트랜잭션에 두면 <b>root 잠금이 배치 끝까지 누적</b>돼 그동안 종결·replay 가 막힌다.
 * 그렇다고 같은 빈의 private 메서드에 {@code @Transactional} 을 붙이면 <b>self-invocation 이라 프록시가
 * 적용되지 않아</b> 경계가 아예 생기지 않는다 — 그래서 <b>별도 빈의 public 메서드</b>여야 한다.
 *
 * <p><b>잠금 순서는 root → target 으로 고정한다.</b> 종결 전파(P5)·재개방(④-c-2b-3 P15)·purge·replay
 * 진입점이 전부 root 를 먼저 잡으므로 순환이 없다. 이전 판본은 <b>잠금 없이</b> 자식 행을 바꿨고,
 * 그 경우 JPA dirty-check 가 행 전체를 쓰므로 동시 종결이 커밋한 {@code RESOLVED} 를 {@code OPEN} 으로
 * 되돌리는 lost update 가 성립했다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeadLetterPublicationWorker {

    private final DeadLetterRecordJpaRepository repository;
    private final OutboxEventJpaRepository outboxEventJpaRepository;

    /**
     * {@code REQUESTED} 행 1건의 발행 결과를 outbox 에서 읽어 종착 상태로 옮긴다.
     *
     * @return 실제로 전이했으면 true
     */
    @Transactional
    public boolean settleOne(Long targetId) {
        // 엔티티가 아니라 root id 만 읽는다 — 여기서 엔티티를 올리면 뒤의 FOR UPDATE 가 잠금만 얻고
        // 관리 중인 인스턴스를 refresh 하지 않아, 잠금을 기다리는 사이의 커밋을 못 본다.
        Optional<Long> rootIdOpt = repository.findRootIdOf(targetId);
        if (rootIdOpt.isEmpty()) {
            log.warn("[DLQ-RECONCILE] 대상 행이 사라졌다 — targetId={}", targetId);
            return false;
        }
        Long rootId = rootIdOpt.get();

        if (repository.findByIdForUpdate(rootId).isEmpty()) {
            // 자식이 가리키는 root 가 없다 — 정합성 결함이므로 조용히 삼키지 않는다.
            log.error("[DLQ-RECONCILE] root 가 없다 — targetId={}, rootId={}", targetId, rootId);
            return false;
        }

        Optional<DeadLetterRecord> targetOpt = rootId.equals(targetId)
                ? repository.findByIdForUpdate(rootId)
                : repository.findByIdForUpdate(targetId);
        if (targetOpt.isEmpty()) {
            log.error("[DLQ-RECONCILE] target 이 없다 — targetId={}, rootId={}", targetId, rootId);
            return false;
        }
        DeadLetterRecord target = targetOpt.get();

        Long outboxEventId = target.getOutboxEventId();
        if (outboxEventId == null) {
            // REQUESTED 인데 연결이 없다 — 진입점이 claim 과 연결을 한 트랜잭션에 넣으므로 정상 경로에선 불가능하다.
            log.error("[DLQ-RECONCILE] REQUESTED 원장 행에 outbox_event_id 가 없다 — recordId={}", targetId);
            return false;
        }

        Optional<OutboxEventStatus> status = outboxEventJpaRepository.findStatusById(outboxEventId);
        if (status.isEmpty()) {
            // fail-closed: 강등하지 않고 그대로 둔다. 다음 사이클에 다시 잡히므로 경보가 계속 울린다.
            // 이 상태는 스스로 해소되지 않으므로 운영자가 publication-unknown 으로 해제한다(④-c-2b-4b).
            log.error("[DLQ-RECONCILE] outbox 행이 없다 — 계약 위반 신호이므로 PUBLISH_FAILED 로 강등하지 않는다. "
                    + "recordId={}, outboxEventId={}", targetId, outboxEventId);
            return false;
        }

        return settle(target, rootId, status.get());
    }

    // PENDING 은 전이 대상이 아니다 — 아직 발행 중이다. 여기서 종착시키면 진행 중인 건이 조기 종결된다.
    private boolean settle(DeadLetterRecord target, Long rootId, OutboxEventStatus outboxStatus) {
        PublicationStatus settled = switch (outboxStatus) {
            case PUBLISHED -> PublicationStatus.PUBLISHED;
            case FAILED -> PublicationStatus.PUBLISH_FAILED;
            default -> null;
        };
        if (settled == null || !target.settlePublication(settled)) {
            return false;
        }

        // drain ⓓ 앵커. PUBLISH_FAILED 에도 찍는다 — poller 는 broker ack 뒤 상태 저장을 따로 하므로
        // 저장 실패로 재시도가 소진되면 **이미 전달된 행이 최종 FAILED** 가 된다(ADR-0020 §D1 crash window).
        // 즉 PUBLISH_FAILED 는 "발행되지 않았다" 가 아니라 "발행 여부를 모른다" 이므로 보수적으로 기록한다.
        repository.stampReplaySettledAt(rootId);

        log.info("[DLQ-RECONCILE] 발행 축 전이 — recordId={}, outboxEventId={}, {}",
                target.getId(), target.getOutboxEventId(), settled);
        return true;
    }
}
