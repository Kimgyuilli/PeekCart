package com.peekcart.global.retention;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Comparator;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * processed_events 멱등성 보존 정책 (ADR-0012 D5).
 *
 * <p>보존기간({@code retention})은 D5 의 4개 창(floor)의 {@code max} 이상이어야 한다 — 교차필드 불변식.
 * 미만이면 {@link AssertTrue} 위반으로 <b>부팅 실패(fail-fast)</b>. 단순 필드 제약이 아니라 필드 간 비교이므로
 * {@code @AssertTrue} cross-field validator 로 강제한다.
 *
 * <p>소유 서비스(product/order/payment/notification)만 {@code @EnableConfigurationProperties} 로 활성화한다.
 * user-service 는 활성화하지 않으므로 floor 검증도 돌지 않는다(cleanup 잡 미소유).
 * 설정키는 전부 base {@code application.yml} 소유(동작 정책 — ADR-0007).
 */
@ConfigurationProperties(prefix = "app.idempotency")
@Validated
@Getter
@Setter
public class IdempotencyRetentionProperties {

    /** processed_events 보존기간. floor 4창의 max 이상이어야 한다. */
    @NotNull
    private Duration retention;

    /**
     * 서비스 간 시계 오차 여유 (ADR-0020 §D4-3).
     *
     * <p>{@code processed_events.processed_at} 은 각 consumer 서비스의 <b>로컬 시각</b>이고
     * 삭제도 로컬 {@code now - retention} 기준이라, 창 경계에서 서비스마다 어긋난다.
     */
    @NotNull
    private Duration clockSkewBudget;

    /**
     * 정리 잡 지연 여유 (ADR-0020 §D4-3).
     *
     * <p>{@code ProcessedEventCleanupScheduler} 는 일 1회 도므로 삭제가 최대 1일 늦을 수 있다.
     */
    @NotNull
    private Duration cleanupSafetyBudget;

    @Valid
    private final Floor floor = new Floor();

    @Valid
    private final Cleanup cleanup = new Cleanup();

    @AssertTrue(message = "app.idempotency.retention 은 floor 4창"
            + "(kafka-topic-retention·max-consumer-downtime·dlq-replay-window·backfill-replay-window)의 "
            + "max 이상이어야 합니다 (ADR-0012 D5 멱등성 창 보호)")
    public boolean isRetentionAtLeastFloor() {
        return retention != null && retention.compareTo(floor.max()) >= 0;
    }

    /**
     * budget 은 <b>양수여야 한다</b> (ADR-0020 §D4-3).
     *
     * <p>{@code @NotNull} 만으로는 {@code 0} 이 통과한다. 둘 다 0이면
     * {@code required == dlqReplayWindow} 가 되어 {@link #isRetentionCoveringReplaySafetyMargin()} 이
     * <b>D4-3 이 제거하려던 7d == 7d 상태를 다시 통과시킨다</b>. 음수는 required 를 아예 낮춘다.
     */
    @AssertTrue(message = "app.idempotency.clock-skew-budget 과 cleanup-safety-budget 은 양수여야 합니다 "
            + "(0 이면 안전여유 규칙이 무력화되어 ADR-0020 D4-3 이 제거하려던 상태가 다시 통과합니다)")
    public boolean isBudgetsPositive() {
        if (clockSkewBudget == null || cleanupSafetyBudget == null) {
            return true; // 누락은 @NotNull 이 잡는다
        }
        return !clockSkewBudget.isNegative() && !clockSkewBudget.isZero()
                && !cleanupSafetyBudget.isNegative() && !cleanupSafetyBudget.isZero();
    }

    /**
     * DLQ replay 안전 여유 (ADR-0020 §D4-3).
     *
     * <p>{@link #isRetentionAtLeastFloor()} 는 <b>등호를 허용</b>한다. 그래서 {@code retention} 과
     * {@code dlq-replay-window} 가 둘 다 7d 이면 통과하는데, 그 상태에서는 <b>광고한 replay 창 전체가
     * 실제로는 보장되지 않는다</b> — 서비스 간 시계 오차와 정리 잡 지연만큼 창 끝이 먼저 잘린다.
     * replay 는 "다른 group 의 멱등 행이 아직 살아 있다"에 소비 효과 1회를 의존하므로(ADR-0020 §D1),
     * 그 여유가 0이면 보장 문구가 사실과 어긋난다.
     *
     * <p>따라서 <b>여유를 설정으로 강제</b>한다. 이 규칙은 도입 시점의 값(7d == 7d)에서 red 이며,
     * 그것이 규칙이 등호를 실제로 막았다는 증거다.
     */
    @AssertTrue(message = "app.idempotency.retention 은 "
            + "dlq-replay-window + clock-skew-budget + cleanup-safety-budget 이상이어야 합니다 "
            + "(ADR-0020 D4-3 replay 안전 여유 — 등호 허용은 창 끝을 보장하지 못합니다)")
    public boolean isRetentionCoveringReplaySafetyMargin() {
        if (retention == null || clockSkewBudget == null || cleanupSafetyBudget == null
                || floor.getDlqReplayWindow() == null) {
            return true; // 누락은 @NotNull 이 별도로 잡는다 — 여기서 중복 실패시키지 않는다
        }
        Duration required = floor.getDlqReplayWindow().plus(clockSkewBudget).plus(cleanupSafetyBudget);
        return retention.compareTo(required) >= 0;
    }

    /**
     * 토픽의 {@code message.timestamp.before.max.ms} 로 쓸 값 (ADR-0020 §D4-1).
     *
     * <p>replay 는 <b>원본 timestamp</b> 를 실어 발행하므로, 이 값이 replay 대상의 나이보다 짧으면
     * 적격 replay 가 broker 에게 거부된다. 하한은 {@code dlqReplayWindow + clockSkewBudget} 인데
     * {@link #isRetentionCoveringReplaySafetyMargin()} 이 {@code retention} 이 그 이상임을 이미
     * 강제하므로, <b>리터럴을 따로 두지 않고 {@code retention} 에서 유도</b>한다.
     */
    public Duration topicMessageTimestampBeforeMax() {
        return retention;
    }

    /** D5 retention floor 입력 4창. */
    @Getter
    @Setter
    public static class Floor {
        @NotNull
        private Duration kafkaTopicRetention;
        @NotNull
        private Duration maxConsumerDowntime;
        @NotNull
        private Duration dlqReplayWindow;
        @NotNull
        private Duration backfillReplayWindow;

        /** 4창 중 최댓값. null 은 무시(누락 창은 @NotNull 이 별도로 잡는다). */
        public Duration max() {
            return Stream.of(kafkaTopicRetention, maxConsumerDowntime, dlqReplayWindow, backfillReplayWindow)
                    .filter(Objects::nonNull)
                    .max(Comparator.naturalOrder())
                    .orElse(Duration.ZERO);
        }
    }

    /** 배치 삭제 계약 — outbox cleanup 도 동일 계약을 공유한다. */
    @Getter
    @Setter
    public static class Cleanup {
        /** 한 batch 당 삭제 상한(unbounded DELETE 방지). */
        private int batchSize = 500;
        /** 한 실행당 batch 반복 상한. */
        private int maxBatchesPerRun = 20;
    }
}
