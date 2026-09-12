package com.peekcart.global.deadletter;

import com.peekcart.global.outbox.OutboxEventJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * DLQ 원장 <b>발행 축</b> reconciler (ADR-0020 §D6-4 · 구현 ④-c-2b-2 P12).
 *
 * <p>{@code publication_status} 를 전이시키는 <b>유일한 주체</b>다. 관리 API 는 {@code REQUESTED} 까지만
 * 만들고(진입점, ④-c-2b-4), 그 뒤의 사실은 outbox 가 정한다 — 발행 결과를 아는 것은 poller 뿐이기 때문이다.
 *
 * <p><b>사건 축({@code status})은 건드리지 않는다.</b> 발행 성공은 사건 해소가 아니다(§D6-2).
 * 두 축을 물리적으로 나눈 이유가 여기서 지켜진다 — 이 클래스가 {@code RESOLVED} 를 쓸 수 있게 되는 순간
 * "broker ack 로 종결" 이 뒷문으로 들어온다.
 *
 * <h2>outbox 행 부재를 실패로 추론하지 않는다</h2>
 * 행이 없다는 것은 <b>실패의 증거가 아니다</b>. 이미 발행된 행이 레거시 cleanup·수동 삭제·정합성 결함으로
 * 사라져도 똑같이 관측된다. 자동으로 {@code PUBLISH_FAILED} 로 강등하면 <b>발행된 사건을 "실패" 로 감사
 * 기록하고 재요청까지 열어준다</b> — 같은 메시지가 두 번 발행된다. §D6-4 는 outbox 가 <b>실제 {@code FAILED}
 * 로 소진된 경우에만</b> 그 전이를 정의한다.
 *
 * <h2>스캔과 전이를 분리한다</h2>
 * 이 클래스는 <b>스캐너</b>다 — {@code REQUESTED} 행의 <b>id 만</b> 모아 건별로
 * {@link DeadLetterPublicationWorker} 에 넘긴다. 트랜잭션 경계는 워커가 가진다(④-c-2b-4a P21).
 * 배치 전체를 한 트랜잭션에 두면 root 잠금이 배치 끝까지 누적돼 그동안 종결·replay 가 막히고,
 * 같은 빈의 private 메서드로 쪼개면 self-invocation 이라 프록시가 적용되지 않는다.
 * <b>한 건의 실패는 그 건만 롤백하고 다음 건을 계속 처리한다.</b>
 *
 * <p>정상 경로에서는 cleanup 제외 조건
 * ({@link OutboxEventJpaRepository#deletePublishedBatchOlderThan})이 부재를 만들지 않는다. 따라서 부재가
 * 관측되면 그것 자체가 <b>계약 위반 신호</b>이며, 경보를 남기고 사람의 판정 대상으로 둔다(계획 §10 R7).
 */
@Slf4j
@Component
@EnableConfigurationProperties(DeadLetterProperties.class)
@RequiredArgsConstructor
public class DeadLetterPublicationReconciler {

    private final DeadLetterRecordJpaRepository repository;
    private final DeadLetterPublicationWorker worker;
    private final DeadLetterProperties properties;

    /**
     * {@code REQUESTED} 행을 훑어 건별 종착을 워커에 위임한다.
     *
     * <p>주기를 짧게 두는 이유: 이 전이가 늦어지면 사건 종결(I-1 가드)이 그만큼 막힌다.
     * outbox poller 와 같은 주기 축에 둔다.
     *
     * <p><b>이 메서드에 {@code @Transactional} 을 붙이지 않는다</b> — 붙이면 워커의 건별 경계가
     * 바깥 트랜잭션에 흡수돼 분리의 목적이 사라진다.
     */
    // 주기 설정화 근거는 OutboxPollingScheduler 와 같다 — 배경 전이가 fixture 상태를 바꾸면
    // "부재를 강등하지 않는다"·"제외 조건이 막는다" 같은 단언이 관측 전에 무너진다.
    @Scheduled(fixedDelayString = "${app.dead-letter.reconcile.delay:5s}")
    @SchedulerLock(name = "deadLetterPublicationReconcileJob", lockAtMostFor = "PT5M", lockAtLeastFor = "PT4S")
    public void reconcile() {
        List<Long> targets = repository.findRequestedPublicationIds(
                PageRequest.of(0, properties.getReconcile().getBatchSize()));

        for (Long targetId : targets) {
            try {
                worker.settleOne(targetId);
            } catch (Exception e) {
                // 한 건의 실패가 배치를 멈추면 뒤의 미결이 전부 지연된다. 그 건만 롤백되고 다음으로 간다.
                log.error("[DLQ-RECONCILE] 건별 종착 실패 — targetId={} (다음 건 계속)", targetId, e);
            }
        }
    }
}
