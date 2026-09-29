package com.peekcart.global.outbox;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peekcart.global.kafka.KafkaTraceHeaders;
import com.peekcart.global.kafka.ReplayHeaders;
import com.peekcart.global.port.SlackPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OutboxPollingService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SlackPort slackPort;
    private final ObjectMapper objectMapper;
    private final int batchSize;
    private final int maxRetry;
    private final Duration publishTimeout;
    private final Duration cycleTimeout;
    private final Timer publishSuccessTimer;
    private final Timer publishFailureTimer;

    public OutboxPollingService(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            SlackPort slackPort,
            ObjectMapper objectMapper,
            MeterRegistry meterRegistry,
            @Value("${app.outbox.polling.batch-size:100}") int batchSize,
            @Value("${app.outbox.polling.max-retry:5}") int maxRetry,
            @Value("${app.outbox.polling.publish-timeout:6s}") Duration publishTimeout,
            @Value("${app.outbox.polling.cycle-timeout:4m}") Duration cycleTimeout) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.slackPort = slackPort;
        this.objectMapper = objectMapper;
        this.batchSize = requirePositive(batchSize, "batchSize");
        this.maxRetry = requirePositive(maxRetry, "maxRetry");
        this.publishTimeout = requirePositive(publishTimeout, "publishTimeout");
        this.cycleTimeout = requirePositive(cycleTimeout, "cycleTimeout");

        // L-009: 발행 처리량 부채화 판단의 선결 표면 (ADR-0009 S8). alert/dashboard 는 비대상.
        registerBacklogGauge(meterRegistry, OutboxEventStatus.PENDING, "pending");
        registerBacklogGauge(meterRegistry, OutboxEventStatus.FAILED, "failed");
        this.publishSuccessTimer = Timer.builder("outbox.publish")
                .description("Outbox 이벤트 Kafka 발행 소요 시간 및 건수")
                .tag("result", "success")
                .register(meterRegistry);
        this.publishFailureTimer = Timer.builder("outbox.publish")
                .description("Outbox 이벤트 Kafka 발행 소요 시간 및 건수")
                .tag("result", "failure")
                .register(meterRegistry);
    }

    private void registerBacklogGauge(MeterRegistry meterRegistry, OutboxEventStatus status, String tag) {
        Gauge.builder("outbox.backlog", outboxEventRepository, repo -> repo.countByStatus(status))
                .description("발행 대기/소진 outbox 이벤트 수 (scrape 시점 집계)")
                .tag("status", tag)
                .register(meterRegistry);
    }

    public void pollAndPublish() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(batchSize);
        long cycleDeadlineNanos = System.nanoTime() + cycleTimeout.toNanos();

        for (OutboxEvent event : pendingEvents) {
            if (System.nanoTime() >= cycleDeadlineNanos) {
                log.warn("Outbox polling cycle 상한 도달 — 잔여 이벤트는 다음 사이클로 이월, cycleTimeout={}",
                        cycleTimeout);
                break;
            }

            long publishStartNanos = System.nanoTime();
            try {
                kafkaTemplate.send(buildRecord(event)).get(publishTimeout.toMillis(), TimeUnit.MILLISECONDS);
                publishSuccessTimer.record(System.nanoTime() - publishStartNanos, TimeUnit.NANOSECONDS);
                event.markPublished();
                outboxEventRepository.save(event);
            } catch (InterruptedException e) {
                publishFailureTimer.record(System.nanoTime() - publishStartNanos, TimeUnit.NANOSECONDS);
                Thread.currentThread().interrupt();
                handlePublishFailure(event, e);
                break;
            } catch (Exception e) {
                publishFailureTimer.record(System.nanoTime() - publishStartNanos, TimeUnit.NANOSECONDS);
                handlePublishFailure(event, e);
            }
        }
    }

    private void handlePublishFailure(OutboxEvent event, Exception e) {
        log.error("Outbox 이벤트 Kafka 발행 실패 — eventId={}, eventType={}: {}",
                event.getEventId(), event.getEventType(), e.getMessage());
        event.incrementRetry();

        if (event.getRetryCount() >= maxRetry) {
            event.markFailed();
            try {
                slackPort.send(String.format(
                        "[Outbox FAILED] eventId=%s, eventType=%s, retryCount=%d",
                        event.getEventId(), event.getEventType(), event.getRetryCount()));
            } catch (Exception slackEx) {
                log.warn("Outbox FAILED Slack 알림 발송 실패 — eventId={}",
                        event.getEventId(), slackEx);
            }
        }

        outboxEventRepository.save(event);
    }

    // kind 분기 (ADR-0020 D3 · 구현 ④-c-2b-2 P11). record_kind 가 REPLAY 인 행만 replay 경로로 간다 —
    // null(구버전 writer)·DOMAIN 은 전부 기존 도메인 경로다(OutboxEvent#isReplay).
    private ProducerRecord<String, String> buildRecord(OutboxEvent event) {
        return event.isReplay() ? buildReplayRecord(event) : buildDomainRecord(event);
    }

    private ProducerRecord<String, String> buildDomainRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.getEventType(), null, event.getAggregateId(), event.getPayload());
        addHeaderIfPresent(record, KafkaTraceHeaders.TRACE_ID, event.getTraceId());
        addHeaderIfPresent(record, KafkaTraceHeaders.USER_ID, event.getUserId());
        return record;
    }

    /**
     * DLQ replay 재발행 레코드를 조립한다 (ADR-0020 D3·D8-3 · 구현 ④-c-2b-2 P11).
     *
     * <p>도메인 경로와 다른 점 셋:
     * <ul>
     *   <li><b>목적지 좌표를 행에서 읽는다</b> — topic 은 event_type 이 아니라 destination_topic 이고,
     *       partition 을 명시한다. 파티션을 브로커 partitioner 에 맡기면 같은 key 의 순서 축을 잃는다(D8-3).</li>
     *   <li><b>timestamp 를 원본 값으로 싣는다</b> — 지정하지 않으면 broker 가 재발행 시각을 찍고,
     *       재실패 시 DLT_ORIGINAL_TIMESTAMP 가 원본을 가리키지 않아 멱등 안전창 계산이 오염된다(D5-3).</li>
     *   <li><b>헤더는 allowlist JSON 에서만 만든다</b> — trace/user 헤더도 붙이지 않는다. 표준 DLT_* 를
     *       실으면 재실패 시 원본 좌표가 덮여 상관 대조의 정본이 사라진다(D3).
     *       <b>키 집합 정확 일치와 4값 유효성을 발행 전에 강제한다</b>(④-c-2b-3a P14-b).</li>
     * </ul>
     *
     * <p>좌표 유효성(destination == origin)은 <b>여기서 검사하지 않는다</b> — 행을 만드는 진입점이
     * 원본 레코드와 대조한 뒤에만 생성한다(구현 ④-c-2b-4). poller 는 이미 승인된 행을 그대로 싣는다.
     */
    private ProducerRecord<String, String> buildReplayRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.getDestinationTopic(),
                event.getDestinationPartition(),
                event.getSourceRecordTimestamp(),
                event.getRecordKey(),
                event.getPayload());
        Map<String, String> headers = replayHeaders(event);
        // **addHeaderIfPresent 를 쓰지 않는다.** 그 메서드는 blank 값을 조용히 생략하는데, 여기서는
        // requireComplete 가 이미 네 값의 유효성을 보장했으므로 생략이 일어나면 그건 계약 위반의 은폐다.
        headers.forEach((key, value) ->
                record.headers().add(key, value.getBytes(StandardCharsets.UTF_8)));
        return record;
    }

    /**
     * allowlist JSON({@code {"k":"v"}}) 만 헤더가 된다. <b>판독 실패도 계약 위반도 삼키지 않는다</b> —
     * 헤더가 없거나 모자란 채로 발행하면 재실패분이 원래 사건에 상관되지 못하고 독립 incident 로
     * 갈라진다(ADR-0020 D5-4). 실패시키면 재시도/{@code PUBLISH_FAILED} 로 드러난다.
     *
     * <p><b>키 집합은 정확히 일치해야 한다</b>(④-c-2b-3a P14-b). "부분집합이면 허용" 으로 두면
     * JSON 이 비었을 때의 빈 Map 과 blank 값 생략이 겹쳐 <b>헤더 0~3개짜리 replay 가 그대로 발행</b>된다 —
     * 발행 측에서 상관 계약을 깨는 경로이므로 판독 측 관대함과 대칭이 아니다.
     */
    private Map<String, String> replayHeaders(OutboxEvent event) {
        String json = event.getReplayHeaders();
        Map<String, String> headers;
        if (json == null || json.isBlank()) {
            headers = Map.of();
        } else {
            try {
                headers = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            } catch (Exception e) {
                throw new IllegalStateException(
                        "replay_headers 판독 실패 — eventId=" + event.getEventId(), e);
            }
        }
        try {
            ReplayHeaders.requireComplete(headers);
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "replay_headers 계약 위반 — eventId=" + event.getEventId() + ": " + e.getMessage(), e);
        }
        return headers;
    }

    // null/blank 모두 미주입 — Consumer 측 MdcRecordInterceptor.headerValue() 의 isBlank ? null 분기와 정합 (ADR-0008)
    private static void addHeaderIfPresent(ProducerRecord<String, String> record, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        record.headers().add(key, value.getBytes(StandardCharsets.UTF_8));
    }

    private static int requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
