package com.peekcart.global.deadletter;

import com.peekcart.global.outbox.OutboxEventJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 교착한 {@code REQUESTED} 의 <b>단방향 해제</b> (구현 ④-c-2b-4b P24 · ADR-0022 §D4).
 *
 * <p><b>왜 필요한가</b>: 원장이 가리키는 outbox 행이 사라지면 {@link DeadLetterPublicationWorker} 는
 * 강등하지 않고 그대로 둔다 — 부재는 실패의 증거가 아니기 때문이다(발행됐는데 행만 지워졌을 수 있다).
 * 그 판단은 옳지만, 그 결과 {@code REQUESTED} 가 <b>스스로 해소되지 않는다</b>: I-1 이 사건 종결을 막고
 * drain ⓐ' 가 0 이 되지 않아 <b>롤백이 영구 차단</b>된다. fail-closed 가 아니라 탈출구 없는 교착이다.
 *
 * <p><b>왜 outbox 부재를 요구하는가</b>: 행이 남아 있으면 reconciler 가 스스로 종착시킨다. 그때도 옮길 수
 * 있게 하면 <b>reconciler 와 경쟁하는 두 번째 종착 경로</b>가 생긴다 — 종결 경로를 하나로 묶어온 계약이
 * 무너지고, 실제로 발행에 성공한 건이 "발행 여부 모름" 으로 후퇴할 수 있다. 이 좁힘이 <b>탈출구와
 * 우회로를 가른다</b>.
 *
 * <p><b>잠금은 root 부터</b> — 종결 전파·재개방·purge·replay claim·reconciler 가 전부 같은 순서로
 * 진입하므로 순환이 없다.
 *
 * <p>대상은 <b>incident 단위</b>다: root 와 활성 자식 중 {@code REQUESTED} 인 행을 전부 본다. 교착이
 * 자식에 있을 수도 있고(자식이 재발행을 받은 경우), 운영자가 자식 id 를 들고 오지 않게 하기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterPublicationOverrideService {

    private final DeadLetterRecordJpaRepository repository;
    private final OutboxEventJpaRepository outboxEventJpaRepository;

    /**
     * @param released 실제로 {@code PUBLISH_UNKNOWN} 으로 옮긴 행 수
     * @param rejections 옮기지 않은 행의 사유 — <b>전량</b> 돌려준다. 첫 사유에서 멈추면 운영자가
     *                   한 번에 한 행씩 확인하기를 반복한다
     */
    public record Result(Long rootId, int released, List<String> rejections) {
    }

    /**
     * incident 안의 교착한 {@code REQUESTED} 를 해제한다.
     *
     * @return 대상 id 가 원장에 없으면 empty
     */
    @Transactional
    public Optional<Result> releaseStuckPublication(Long id, String overrideBy, String reason) {
        // 엔티티가 아니라 root id 만 읽는다 — 여기서 엔티티를 올리면 뒤의 FOR UPDATE 가 잠금만 얻고
        // 관리 중인 인스턴스를 refresh 하지 않아, 잠금을 기다리는 사이의 커밋을 못 본다.
        Optional<Long> rootIdOpt = repository.findRootIdOf(id);
        if (rootIdOpt.isEmpty()) {
            return Optional.empty();
        }

        Long rootId = rootIdOpt.get();
        Optional<DeadLetterRecord> locked = repository.findByIdForUpdate(rootId);
        if (locked.isEmpty()) {
            log.error("DLQ 원장 자식이 가리키는 root 가 없다 — id={}, rootRecordId={}", id, rootId);
            return Optional.empty();
        }

        List<DeadLetterRecord> candidates = new ArrayList<>();
        candidates.add(locked.get());
        candidates.addAll(repository.findChildrenForUpdate(rootId));

        List<String> rejections = new ArrayList<>();
        int released = 0;
        boolean sawRequested = false;

        for (DeadLetterRecord candidate : candidates) {
            if (candidate.getPublicationStatus() != PublicationStatus.REQUESTED) {
                continue;
            }
            sawRequested = true;

            Long outboxEventId = candidate.getOutboxEventId();
            if (outboxEventId == null) {
                // 진입점이 claim 과 연결을 한 트랜잭션에 넣으므로 정상 경로에선 불가능하다.
                // 그래도 해제 대상으로 삼는다 — 연결이 없으면 reconciler 도 영원히 종착시키지 못한다.
                log.error("[DLQ-OVERRIDE] REQUESTED 인데 outbox_event_id 가 없다 — recordId={}", candidate.getId());
            } else if (outboxEventJpaRepository.findStatusById(outboxEventId).isPresent()) {
                rejections.add("outbox 행이 아직 있다 — reconciler 가 종착시킨다. recordId=" + candidate.getId()
                        + ", outboxEventId=" + outboxEventId);
                continue;
            }

            if (repository.overridePublicationUnknown(candidate.getId(), overrideBy, reason) == 0) {
                // 조건부 UPDATE 가 0 행 — 잠금 밖의 주체가 그 사이에 전이시켰다.
                rejections.add("이미 다른 주체가 전이시켰다 — recordId=" + candidate.getId());
                continue;
            }
            released++;
            log.warn("[DLQ-OVERRIDE] 발행 축 교착 해제 — recordId={}, rootId={}, by={}, reason={}",
                    candidate.getId(), rootId, overrideBy, reason);
        }

        if (!sawRequested) {
            rejections.add("이 incident 에 REQUESTED 인 행이 없다 — rootId=" + rootId);
        }

        if (released > 0) {
            // drain ⓓ 앵커는 이때도 찍는다 — 발행 여부를 모르므로 소비 재시도가 끝났다고 가정할 수 없다.
            repository.stampReplaySettledAt(rootId);
        }

        return Optional.of(new Result(rootId, released, List.copyOf(rejections)));
    }
}
