package com.peekcart.global.deadletter;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * DLQ 원장 종결 전이 (구현 ④-c-2b-1 P5 · ADR-0020 §D5-4).
 *
 * <p><b>종결의 단위는 행이 아니라 incident 다.</b> 재발행이 실패할 때마다 자식 행이 생기므로,
 * root 만 닫으면 자식이 미결로 남고 자식만 닫으면 <b>미결을 종결로 위장</b>한다. 그래서:
 * <ul>
 *   <li>대상 id 가 자식이면 <b>canonical root 로 정규화</b>한다</li>
 *   <li>root 를 잠근 뒤 root 와 <b>활성 자식 전부</b>를 같은 트랜잭션에서 전이한다</li>
 *   <li>{@code acknowledge}/{@code resolve}/{@code discard} 셋 다 이 경로를 쓴다 — 하나라도 빠지면 축이 갈라진다</li>
 * </ul>
 *
 * <p><b>잠금은 항상 root 부터</b> 잡는다. 재개방(④-c-2b-3)·purge 도 같은 순서로 진입하므로 순환이 없다.
 *
 * <p>발행 축({@code publication_status})은 여기서 건드리지 않는다 — 전이 주체는 reconciler 1종이다(§D6-4).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeadLetterTransitionService {

    private final DeadLetterRecordJpaRepository repository;

    /**
     * 전이 결과. {@code changed} 는 root 또는 자식 중 하나라도 실제로 전이했는지다.
     *
     * <p><b>{@code changed=false} 의 두 경우를 {@code rejectedReason} 으로 구분한다</b>
     * (구현 ④-c-2b-4a P21 · 계획 리뷰 3R #7):
     * <ul>
     *   <li>이미 terminal 이라 no-op — 멱등이며 {@code rejectedReason} 은 {@code null}</li>
     *   <li>I-1 이 거부 — {@code rejectedReason} 이 사유를 담는다</li>
     * </ul>
     * 구분하지 않으면 운영자가 "이미 닫혔다" 와 "닫지 못했다" 를 가릴 수 없다.
     */
    public record Result(Long rootId, String status, boolean changed, int affectedChildren,
                         String rejectedReason) {
    }

    @Transactional
    public Optional<Result> acknowledge(Long id, String actor) {
        // acknowledge 는 I-1 가드에서 면제된다 — ADR-0020 I-1 이 금지하는 것은 **terminal resolution**
        // 이고 OPEN → ACKED 는 terminal 이 아니다(§D6-2b). 세 전이가 같은 경로를 공유하므로
        // 공통 precheck 에 가드를 넣으면 확인까지 막혀 기존 멱등 계약이 깨진다(계획 리뷰 3R #7).
        return transition(id, record -> record.acknowledge(actor), false);
    }

    @Transactional
    public Optional<Result> resolve(Long id, String actor, String evidence) {
        return transition(id, record -> record.resolve(actor, evidence), true);
    }

    @Transactional
    public Optional<Result> discard(Long id, String actor, String reason) {
        return transition(id, record -> record.discard(actor, reason), true);
    }

    /**
     * @param guardPublication I-1 — 발행 결과가 미확정({@code REQUESTED})인 incident 의 종결을 막는다
     * @return 대상 id 가 원장에 없으면 empty
     */
    private Optional<Result> transition(Long id, Function<DeadLetterRecord, Boolean> apply,
                                        boolean guardPublication) {
        // **엔티티가 아니라 root id 만 읽는다.** 여기서 엔티티를 읽으면 그 인스턴스가 영속성 컨텍스트에
        // 들어가고, 뒤의 SELECT ... FOR UPDATE 가 **잠금은 얻되 상태를 refresh 하지 않아** 잠금을
        // 기다리는 동안 다른 트랜잭션이 커밋한 terminal 전이를 못 본다. 그러면 "이미 terminal 이면 no-op"
        // 계약이 깨지고 나중 요청이 앞선 종결을 덮어쓴다.
        Optional<Long> rootIdOpt = repository.findRootIdOf(id);
        if (rootIdOpt.isEmpty()) {
            return Optional.empty();
        }

        Long rootId = rootIdOpt.get();
        Optional<DeadLetterRecord> locked = repository.findByIdForUpdate(rootId);
        if (locked.isEmpty()) {
            // 자식이 가리키는 root 가 사라진 상태 — 데이터 정합 문제이므로 조용히 삼키지 않는다.
            log.error("DLQ 원장 자식이 가리키는 root 가 없다 — id={}, rootRecordId={}", id, rootId);
            return Optional.empty();
        }

        DeadLetterRecord root = locked.get();
        // 조회가 이미 활성 자식만 돌려준다(terminal 은 잠그지도 않는다).
        List<DeadLetterRecord> children = repository.findChildrenForUpdate(rootId);

        // **2단계다: 전부 잠그고 → 전부 검사하고 → 그 다음에만 적용한다** (계획 리뷰 1R #4).
        // 검사를 행별 적용 안에 넣으면 root 를 전이한 뒤 자식의 REQUESTED 를 발견해 **부분 전이**가 남는다.
        if (guardPublication) {
            String blocking = blockingPublication(root, children);
            if (blocking != null) {
                return Optional.of(new Result(rootId, root.getStatus(), false, 0, blocking));
            }
        }

        boolean rootChanged = apply.apply(root);
        int affected = 0;
        for (DeadLetterRecord child : children) {
            if (apply.apply(child)) {
                affected++;
            }
        }

        if (rootChanged || affected > 0) {
            log.info("DLQ 원장 전이 — rootId={}, status={}, 자식 {}건 (요청 id={})",
                    rootId, root.getStatus(), affected, id);
        }
        return Optional.of(new Result(rootId, root.getStatus(), rootChanged || affected > 0, affected, null));
    }

    /**
     * I-1 — root 또는 <b>활성 자식 중 하나라도</b> {@code REQUESTED} 면 종결할 수 없다 (ADR-0020 I-1).
     *
     * <p>발행 결과가 미확정인 사건을 닫으면 그 뒤 도착한 결과가 종결된 원장에 반영되지 못한다.
     *
     * <p><b>잠금이 원자성의 근거다</b> — 이 읽기는 root·자식을 전부 {@code FOR UPDATE} 로 잡은 뒤에
     * 일어나고, replay claim(P21)도 같은 순서로 root 를 먼저 잡는다. 그래서 조건부 UPDATE 없이도
     * "읽고 검사하고 쓰기" 가 직렬화된다. {@code NULL}(요청 없음)에서의 종결은 허용된다(§D6-2b 표).
     */
    private String blockingPublication(DeadLetterRecord root, List<DeadLetterRecord> children) {
        if (root.getPublicationStatus() == PublicationStatus.REQUESTED) {
            return "발행 결과가 미확정이다(REQUESTED) — rootId=" + root.getId();
        }
        for (DeadLetterRecord child : children) {
            if (child.getPublicationStatus() == PublicationStatus.REQUESTED) {
                return "활성 자식의 발행 결과가 미확정이다(REQUESTED) — childId=" + child.getId();
            }
        }
        return null;
    }
}
