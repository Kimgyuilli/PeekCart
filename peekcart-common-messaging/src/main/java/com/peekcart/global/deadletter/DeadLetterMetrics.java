package com.peekcart.global.deadletter;

import com.peekcart.global.metrics.CommitAwareMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * DLQ 원장 잔량 메트릭 (계획 ④-d-1 P3 · 부모 P11).
 *
 * <p><b>{@link DeadLetterEndpoint} 와 같은 쿼리를 쓴다.</b> 두 표면이 서로 다른 방식으로 세면
 * 값이 갈라졌을 때 어느 쪽이 맞는지 알 수 없다 — 그러면 둘 다 신뢰할 수 없게 된다.
 *
 * <p>actuator 조회 표면은 유지한다. 메트릭은 시계열·alert 용이고, actuator 는 운영자가 지금
 * 바로 물어보는 용도다(④-c-2a §2.6-E).
 *
 * <p><b>집계 단위는 행이 아니라 incident(root) 다</b>(ADR-0020 §D6-3). 재발행이 실패할 때마다 자식 행이
 * 늘어나므로 행으로 세면 backlog·oldest-age 가 사건 수보다 계속 부풀고 alert 임계값이 의미를 잃는다.
 * PromQL 식(`k8s/monitoring/shared/grafana-alerts.yml`)은 메트릭 이름만 참조하므로 <b>변경이 없다</b>.
 *
 * <p><b>토픽·group 별 분해는 두지 않는다.</b> 태그로 달면 시계열이 (토픽 × group) 으로 늘어난다.
 * 잔량 2종으로 "쌓이고 있는가" 를 답할 수 있고, 무엇이 쌓이는지는 원장을 조회하면 된다.
 */
@Component
public class DeadLetterMetrics {

    /** 상관 실패 사유. <b>bounded 리터럴 집합</b>이다 — 입력에서 온 문자열을 태그로 쓰지 않는다. */
    public enum CorrelationReason {
        /** replay 를 주장하지 않는 레코드 (최초 실패 포함). 분모라서 함께 센다. */
        NO_ATTEMPT_HEADER("no_attempt_header"),
        /** 헤더의 attempt-id 를 가진 root 가 원장에 없다 (로케이터 탐색 실패). */
        ATTEMPT_NOT_FOUND("attempt_not_found"),
        OWNER_MISMATCH("owner_mismatch"),
        GROUP_MISMATCH("group_mismatch"),
        ROOT_ID_MISMATCH("root_id_mismatch"),
        TOPIC_MISMATCH("topic_mismatch"),
        FINGERPRINT_MISMATCH("fingerprint_mismatch"),
        DIGEST_MISMATCH("digest_mismatch"),
        /** 잠금 후 대조에서 root 의 attempt 가 이미 다른 값이었다 (TOCTOU). */
        ATTEMPT_CHANGED("attempt_changed"),
        /** 상관 성공. {@code result=correlated} 일 때의 고정 값이다. */
        NONE("none");

        private final String tag;

        CorrelationReason(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final MeterRegistry registry;

    public DeadLetterMetrics(MeterRegistry registry, DeadLetterRecordJpaRepository repository) {
        this.registry = registry;

        Gauge.builder("dlq.backlog", repository, DeadLetterRecordJpaRepository::countUnresolved)
                .description("미결(OPEN/ACKED) DLQ incident 건수 — 재발행 재실패로 늘어난 자식 행은 세지 않는다")
                .register(registry);

        Gauge.builder("dlq.oldest.age", repository, DeadLetterMetrics::oldestAgeSeconds)
                .description("가장 오래된 미결 DLQ incident 의 경과 시간(초). 미결 0건이면 0")
                .baseUnit("seconds")
                .register(registry);
    }

    /**
     * 상관 판정 1건 (계획 ④-c-2b-3b P15-h).
     *
     * <p><b>증가 기준은 "신규 INSERT 가 확정된 상관 결과"</b> 다. 대조는 INSERT 보다 <b>먼저</b> 돌므로
     * (P15 단계 3 → 4) 중복 유입에도 대조 자체는 계산된다 — 그것까지 세면 broker 재전달이 통계를 부풀린다.
     *
     * <p>{@code CommitAwareMetrics} 를 쓰는 이유: 트랜잭션 안에서 올리면 <b>rollback 된 상관까지 세도
     * green</b> 이다(④-d-1 3R #1 이 정확히 그 결함이었다). callback 예외도 그쪽에서 격리한다.
     */
    public void recordCorrelation(boolean correlated, CorrelationReason reason) {
        Counter counter = Counter.builder("dlq.correlation")
                .description("DLQ 재실패의 원장 앵커 상관 판정 — 신규 적재가 확정된 건만 센다")
                .tag("result", correlated ? "correlated" : "independent")
                .tag("reason", reason.tag())
                .register(registry);
        CommitAwareMetrics.increment(counter);
    }

    /**
     * 종결된 incident 가 다시 열린 횟수 (ADR-0020 §D6-2b I-2).
     *
     * <p><b>terminal → OPEN 전이에만</b> 센다. 이미 {@code OPEN}/{@code ACKED} 인 root 에 자식이 붙는 것은
     * 정상이고 재개방이 아니다.
     */
    public void recordReopened() {
        Counter counter = Counter.builder("dlq.reopened")
                .description("사람이 종결한 DLQ incident 가 재실패 상관으로 다시 열린 횟수")
                .register(registry);
        CommitAwareMetrics.increment(counter);
    }

    private static double oldestAgeSeconds(DeadLetterRecordJpaRepository repository) {
        return repository.findOldestUnresolvedOccurredAt()
                .map(oldest -> (double) Duration.between(oldest, LocalDateTime.now()).toSeconds())
                .orElse(0.0);
    }
}
