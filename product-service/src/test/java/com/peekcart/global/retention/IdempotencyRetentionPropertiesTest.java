package com.peekcart.global.retention;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * retention floor 교차필드 불변식 검증 + max 계산 (ADR-0012 D5).
 * 이 검증이 부팅 시 fail-fast 의 근거다({@code @Validated @ConfigurationProperties}).
 */
@DisplayName("IdempotencyRetentionProperties — floor cross-field 검증 + max 계산")
class IdempotencyRetentionPropertiesTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** budget 2개는 ADR-0020 D4-3 도입값으로 채운다 — floor 규칙만 보는 케이스가 @NotNull 로 흔들리지 않게. */
    private IdempotencyRetentionProperties props(Duration retention,
                                                 Duration kafka, Duration downtime,
                                                 Duration dlq, Duration backfill) {
        return props(retention, kafka, downtime, dlq, backfill,
                Duration.ofMinutes(5), Duration.ofDays(1));
    }

    private IdempotencyRetentionProperties props(Duration retention,
                                                 Duration kafka, Duration downtime,
                                                 Duration dlq, Duration backfill,
                                                 Duration clockSkew, Duration cleanupSafety) {
        IdempotencyRetentionProperties p = new IdempotencyRetentionProperties();
        p.setRetention(retention);
        p.setClockSkewBudget(clockSkew);
        p.setCleanupSafetyBudget(cleanupSafety);
        p.getFloor().setKafkaTopicRetention(kafka);
        p.getFloor().setMaxConsumerDowntime(downtime);
        p.getFloor().setDlqReplayWindow(dlq);
        p.getFloor().setBackfillReplayWindow(backfill);
        return p;
    }

    @Test
    @DisplayName("floor.max() 는 4창의 최댓값을 돌려준다")
    void floorMaxReturnsLargest() {
        IdempotencyRetentionProperties p = props(
                Duration.ofDays(7), Duration.ofDays(3), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(5));
        assertThat(p.getFloor().max()).isEqualTo(Duration.ofDays(7));
    }

    @Test
    @DisplayName("retention 이 floor max 와 안전여유를 모두 넘으면 위반 없음")
    void validWhenRetentionAtLeastFloorAndMargin() {
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                Duration.ofDays(9), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7)));
        assertThat(violations).isEmpty();
    }

    // ── ADR-0020 D4-3: replay 안전 여유 ────────────────────────────────────────
    // 도입 이전 값(retention 7d == dlq-replay-window 7d)은 floor 규칙만 보면 통과했다.
    // 그 상태에서는 시계 오차·정리 잡 지연만큼 창 끝이 먼저 잘려 광고한 replay 창이
    // 실제로 보장되지 않는다. 아래 두 테스트가 "등호를 실제로 막았다" 는 증거다.

    @Test
    @DisplayName("도입 이전 값(7d == dlq-replay-window 7d)은 안전여유 규칙에서 위반이다")
    void violationWhenNoSafetyMargin() {
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                Duration.ofDays(7), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7)));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString()
                        .equals("retentionCoveringReplaySafetyMargin"));
    }

    @Test
    @DisplayName("경계: retention == dlqReplayWindow + clockSkew + cleanupSafety 이면 통과 (등호 허용)")
    void validAtExactMarginBoundary() {
        Duration exact = Duration.ofDays(7).plusMinutes(5).plusDays(1);
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                exact, Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7),
                Duration.ofMinutes(5), Duration.ofDays(1)));
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("경계: 1ms 모자라면 위반")
    void violationJustBelowMarginBoundary() {
        Duration justBelow = Duration.ofDays(7).plusMinutes(5).plusDays(1).minusMillis(1);
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                justBelow, Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7),
                Duration.ofMinutes(5), Duration.ofDays(1)));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString()
                        .equals("retentionCoveringReplaySafetyMargin"));
    }

    @Test
    @DisplayName("budget 누락은 @NotNull 이 잡고, 안전여유 규칙은 중복 실패시키지 않는다")
    void nullBudgetReportedOnlyByNotNull() {
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                Duration.ofDays(9), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7), null, Duration.ofDays(1)));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("clockSkewBudget"));
        assertThat(violations).noneMatch(v -> v.getPropertyPath().toString()
                .equals("retentionCoveringReplaySafetyMargin"));
    }

    @Test
    @DisplayName("topicMessageTimestampBeforeMax() 는 retention 에서 유도한다 (리터럴 중복 금지)")
    void timestampBeforeMaxDerivesFromRetention() {
        IdempotencyRetentionProperties p = props(
                Duration.ofDays(9), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7));
        assertThat(p.topicMessageTimestampBeforeMax()).isEqualTo(Duration.ofDays(9));
    }

    @Test
    @DisplayName("retention < floor max 이면 @AssertTrue 위반 (fail-fast 근거)")
    void violationWhenRetentionBelowFloor() {
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                Duration.ofDays(1), Duration.ofDays(7), Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7)));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("retentionAtLeastFloor"));
    }

    @Test
    @DisplayName("floor 창 누락(null)이면 @NotNull 위반 (@Valid cascade)")
    void violationWhenFloorWindowNull() {
        Set<ConstraintViolation<IdempotencyRetentionProperties>> violations = validator.validate(props(
                Duration.ofDays(7), null, Duration.ofHours(24),
                Duration.ofDays(7), Duration.ofDays(7)));
        assertThat(violations)
                .anyMatch(v -> v.getPropertyPath().toString().equals("floor.kafkaTopicRetention"));
    }
}
