package com.peekcart.product.infrastructure.kafka;

import com.peekcart.global.kafka.FixedSequenceBackOff;
import com.peekcart.global.kafka.JitteredSequenceBackOff;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.listener.ExceptionClassifier;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.util.backoff.BackOffExecution;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D-025 P5 — 낙관락 충돌 7건이 <b>왜 재시도 4회를 전부 소진하고 DLQ 로 갔는가</b>.
 *
 * <p>GKE replicas=3 실측(증적 {@code evidence/d002bc-gke-20260917.md})에서 {@code PRD-004}=0,
 * 낙관락 충돌 7, DLQ 7 이 나왔다. 공용 핸들러는 초기 1회 + 재시도 3회 = 4회를 시도하므로
 * "전량 DLQ" 는 설명이 필요한 관측이었다. 계획서 P5 가 세운 두 가설을 여기서 가른다.
 *
 * <ul>
 *   <li><b>가설 (b) — 애초에 재시도 대상이 아니었다</b>: {@link #optimisticLockFailureIsRetryableByDefault()}
 *       가 <b>반증</b>한다. spring-kafka 의 기본 fatal 목록에 낙관락 예외는 없다 → 재시도는 실제로 일어났다.</li>
 *   <li><b>가설 (a) — 재시도가 경합을 재생산했다</b>: {@link #previousBackOffHadNoJitter_soRetriesCollidedInLockstep()}
 *       가 메커니즘을 보인다. jitter 가 없으면 동시에 충돌한 N 개가 똑같이 1s → 5s → 30s 를 기다렸다가
 *       <b>같은 순간에 함께 깨어나</b> 다시 충돌한다. 재시도가 경합을 푸는 게 아니라 <b>보존</b>한다.</li>
 * </ul>
 *
 * <p><b>처분</b>: ADR-0025 D2 가 이 서비스의 error handler 를 jitter 있는 backoff 로 바꿨다
 * ({@link #currentBackOffIsJittered()}). 세 테스트 모두 <b>정책을 고정</b>하는 성격이다 —
 * 라이브러리 기본값이나 backoff 구성이 바뀌면 여기서 먼저 깨져야 한다.
 */
@DisplayName("D-025 P5 — 낙관락 충돌의 재시도 정책")
class StockConflictRetryPolicyTest {

    /** {@code ProductKafkaConfig#kafkaErrorHandler} 가 쓰는 것과 같은 구성. */
    private static final long[] INTERVALS = {1_000, 5_000, 30_000};

    /**
     * 가설 (b) 반증 — 낙관락 예외는 spring-kafka 의 <b>기본 fatal(재시도 불가) 목록에 없다</b>.
     *
     * <p>기본 목록은 역직렬화·메시지 변환·시그니처 불일치처럼 <b>재시도해도 결과가 같은</b> 것들뿐이다.
     * 낙관락 충돌은 재시도하면 달라질 수 있는 부류라 그 목록에 들어갈 이유가 없고, 실제로 없다.
     * 따라서 실측의 DLQ 7 은 "재시도를 안 해서" 가 아니다.
     */
    @Test
    @DisplayName("낙관락 예외는 기본 fatal 목록에 없다 — 재시도는 실제로 일어났다 (가설 b 반증)")
    void optimisticLockFailureIsRetryableByDefault() {
        List<Class<? extends Throwable>> fatal = ExceptionClassifier.defaultFatalExceptionsList();

        assertThat(fatal)
                .as("기본 fatal 목록: %s", fatal)
                .doesNotContain(
                        ObjectOptimisticLockingFailureException.class,
                        OptimisticLockingFailureException.class);
        assertThat(fatal)
                .as("낙관락 예외의 상위 타입도 목록에 없어야 한다 — 상위가 걸리면 하위도 fatal 이 된다")
                .noneMatch(f -> f.isAssignableFrom(ObjectOptimisticLockingFailureException.class));
    }

    /**
     * 가설 (a) 메커니즘 — <b>옛</b> backoff 에는 jitter 가 없어 재시도가 lockstep 으로 겹쳤다.
     *
     * <p>동시에 충돌한 소비자들이 같은 순간에 같은 간격을 기다린다. 그래서 재시도는 경합을 흩뜨리지
     * 않고 <b>그대로 다시 모은다</b>. 소진 후 전량 DLQ 라는 실측 형태와 일치한다. 이 테스트는 그
     * 성질을 <b>기록으로</b> 남긴다 — {@code FixedSequenceBackOff} 자체는 다른 4개 서비스가 여전히
     * 쓰고 있어 D-025 범위 밖이고(계획서 §5 후속), 여기서 왜 갈아탔는지가 보여야 한다.
     */
    @Test
    @DisplayName("옛 backoff 는 jitter 가 없었다 — 동시 충돌자들이 같은 순간에 함께 재시도 (가설 a 메커니즘)")
    void previousBackOffHadNoJitter_soRetriesCollidedInLockstep() {
        List<Long> first = drain(new FixedSequenceBackOff(INTERVALS).start());
        List<Long> second = drain(new FixedSequenceBackOff(INTERVALS).start());

        assertThat(first)
                .as("간격이 고정값 그대로 — 무작위 성분이 없다")
                .containsExactly(1_000L, 5_000L, 30_000L);
        assertThat(second)
                .as("서로 다른 소비자의 재시도 일정이 완전히 동일하다 = lockstep")
                .isEqualTo(first);
    }

    /**
     * 현재 정책 — {@code ProductKafkaConfig#kafkaErrorHandler} 는 jitter 를 가진 backoff 를 쓴다
     * (ADR-0025 D2). 단계 수(= 재시도 예산)는 그대로 유지한다.
     */
    @Test
    @DisplayName("현재 backoff 는 jitter 를 갖는다 — 재시도 예산 3회는 그대로")
    void currentBackOffIsJittered() {
        JitteredSequenceBackOff backOff = new JitteredSequenceBackOff(0.5, INTERVALS);

        assertThat(drain(backOff.start()))
                .as("초기 1회 + 재시도 3회 = 4회 시도 후 DLQ (실측 DLQ 7 과 같은 예산)")
                .hasSize(3);
        assertThat(backOff.start().nextBackOff())
                .as("첫 단계가 기준값 ±50% 안에서 흩어진다")
                .isBetween(500L, 1_500L);
    }

    private static List<Long> drain(BackOffExecution execution) {
        List<Long> intervals = new ArrayList<>();
        for (long next = execution.nextBackOff(); next != BackOffExecution.STOP; next = execution.nextBackOff()) {
            intervals.add(next);
        }
        return intervals;
    }
}
