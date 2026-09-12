package com.peekcart.global.deadletter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * DLQ 원장 운영 계약 (계획 ④-c-2a P5·P10).
 *
 * <p><b>왜 {@code clusterId} 와 {@code topicGeneration} 이 설정값인가</b>: 원장의 물리 식별자
 * {@code (topic, partition, offset, group)} 은 토픽 재생성 시 유일하지 않다 — 동명 재생성이면
 * offset 이 0부터 재사용되어 과거 행과 충돌하고, {@code INSERT IGNORE} 때문에 <b>새 실패가 정상
 * 중복처럼 조용히 폐기</b>된다. 브로커 topic UUID 를 안정적으로 얻을 수 없는 환경을 전제로,
 * 운영이 관리하는 <b>세대 번호</b>를 키에 넣는다.
 *
 * <p><b>기본값을 암묵 허용하지 않는다.</b> 미등록 토픽의 generation 을 참조하면
 * {@link #generationOf(String)} 이 예외를 던지고, {@code cluster-id} 가 비면 부팅이 실패한다.
 * 조용히 {@code 1} 로 떨어지면 토픽 재생성 후 세대 bump 를 잊어도 아무도 모른다 — 그게 정확히
 * 이 컬럼이 막으려던 사고다.
 *
 * <p>설정키는 base {@code application.yml} 소유다(동작 정책 — ADR-0007).
 */
@ConfigurationProperties(prefix = "app.dead-letter")
@Validated
@Getter
@Setter
public class DeadLetterProperties {

    /** {@code DeadLetterPublishingRecoverer} 의 목적지 접미사. */
    private static final String DLQ_SUFFIX = ".dlq";

    /** Kafka 클러스터 식별자. 클러스터를 갈아끼우면 좌표 공간이 달라진다. */
    @NotBlank
    private String clusterId;

    /**
     * 토픽별 프로비저닝 세대. 토픽을 삭제·재생성하면 <b>배포 전에</b> 값을 올린다(runbook §generation bump).
     * 키는 {@code .dlq} 가 아니라 <b>원본 토픽</b> 이름이다.
     */
    @NotNull
    private Map<String, Integer> topicGenerations = new HashMap<>();

    @Valid
    private final Alert alert = new Alert();

    @Valid
    private final Payload payload = new Payload();

    @Valid
    private final Purge purge = new Purge();

    @Valid
    private final Reconcile reconcile = new Reconcile();

    /** replay 개시 kill-switch. */
    private final Replay replay = new Replay();

    /**
     * 해당 토픽의 세대를 돌려준다.
     *
     * <p><b>{@code .dlq} 토픽은 원본 토픽의 세대를 따른다.</b> origin 헤더를 판독하지 못한 행
     * ({@code DLQ_ORIGIN})은 좌표로 <b>DLQ 토픽 자신의 이름</b>을 싣는데, 그것까지 전부 등록하면
     * 설정이 두 배가 된다. 둘은 같은 서비스가 함께 선언하고 함께 재생성하므로 세대를 공유한다.
     *
     * <p>정확 일치를 먼저 보므로, {@code .dlq} 만 따로 재생성한 경우에는 그 이름으로 항목을 추가해
     * 원본과 다른 세대를 줄 수 있다.
     *
     * @throws IllegalStateException 미등록 토픽 — 설정 누락을 조용히 넘기지 않는다
     */
    public int generationOf(String topic) {
        Integer generation = topicGenerations.get(topic);
        if (generation == null && topic != null && topic.endsWith(DLQ_SUFFIX)) {
            generation = topicGenerations.get(topic.substring(0, topic.length() - DLQ_SUFFIX.length()));
        }
        if (generation == null) {
            throw new IllegalStateException(
                    "app.dead-letter.topic-generations 에 '" + topic + "' 가 없습니다. "
                            + "토픽을 추가하면 세대를 함께 등록해야 합니다 (기본값 암묵 허용 금지)");
        }
        return generation;
    }

    @AssertTrue(message = "app.dead-letter.topic-generations 의 세대는 1 이상이어야 합니다")
    public boolean isGenerationsPositive() {
        return topicGenerations.values().stream().allMatch(v -> v != null && v >= 1);
    }

    /**
     * 미결 경보 계약. 임계값 없는 "경보한다" 는 mock 호출 1회로 통과하는 false-green 이라
     * 값을 설정으로 고정한다.
     */
    @Getter
    @Setter
    public static class Alert {
        /** 이 시간을 넘긴 미결 건은 경보 대상. */
        @NotNull
        private Duration staleAfter = Duration.ofHours(24);
        /** 미결 건수가 이 값을 넘으면 경보. */
        private int backlogThreshold = 50;
        /** 같은 경보의 재발송 억제 간격. 없으면 스케줄 주기마다 도배된다. */
        @NotNull
        private Duration cooldown = Duration.ofHours(6);
        /** 한 번에 조회할 stale 건 상한 (unbounded 조회 방지). */
        private int scanLimit = 100;
    }

    /** payload 보존 정책. 원장 payload 는 <b>진단용</b>이며 replay 원본이 아니다. */
    @Getter
    @Setter
    public static class Payload {
        /** 저장 상한(문자). 초과분은 잘리고 {@code payload_truncated} 로 표시한다. */
        private int maxLength = 8000;
    }

    /**
     * 발행 축 reconciler (ADR-0020 §D6-4 · 구현 ④-c-2b-2 P12).
     * {@code publication_status} 를 전이시키는 <b>유일한 주체</b>이며, 관리 API 는 {@code REQUESTED} 까지만 만든다.
     */
    @Getter
    @Setter
    public static class Reconcile {
        /** 한 사이클에 대조할 {@code REQUESTED} 행 상한 (unbounded 조회 방지). */
        private int batchSize = 200;
    }

    /**
     * replay 개시 (ADR-0020 §D5 · 구현 ④-c-2b-4a P21·P24).
     *
     * <p><b>기본값이 {@code false} 인 것이 계약이다</b>(계획 리뷰 1R #6). 기본 {@code true} 면 새 Pod 가
     * Ready 가 되는 순간 backfill·증적·리허설 이전에 replay API 가 열리고, 그것을 다시 닫는 데
     * <b>롤링 재기동이 또 든다</b>(Spring 정적 설정이라 ConfigMap 갱신만으로는 반영되지 않는다).
     * 닫힌 채로 배포하고 준비가 끝난 뒤 <b>의도적으로 여는</b> 방향이 되돌리기 비용이 낮다.
     *
     * <p>drain·롤백 계약(④-c-2b-4b)이 서기 전까지는 운영에서 열지 않는다.
     */
    @Getter
    @Setter
    public static class Replay {
        /** {@code false} 면 {@code action=replay} 가 거부된다. */
        private boolean enabled = false;
    }

    /** 종결 건 정리. {@code OPEN}/{@code ACKED} 는 대상이 아니다 — 장기 미결은 운영 SLA 문제다. */
    @Getter
    @Setter
    public static class Purge {
        /** {@code DISCARDED} 후 이 기간이 지난 건만 삭제. */
        @NotNull
        private Duration retention = Duration.ofDays(90);
        private int batchSize = 500;
        private int maxBatchesPerRun = 20;
    }
}
