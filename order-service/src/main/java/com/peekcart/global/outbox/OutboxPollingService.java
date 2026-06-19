package com.peekcart.global.outbox;

import com.peekcart.global.kafka.KafkaTraceHeaders;
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
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class OutboxPollingService {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final SlackPort slackPort;
    private final int batchSize;
    private final int maxRetry;
    private final Duration publishTimeout;
    private final Duration cycleTimeout;
    private final List<String> aggregateTypes;
    private final Timer publishSuccessTimer;
    private final Timer publishFailureTimer;

    public OutboxPollingService(
            OutboxEventRepository outboxEventRepository,
            KafkaTemplate<String, String> kafkaTemplate,
            SlackPort slackPort,
            MeterRegistry meterRegistry,
            @Value("${app.outbox.polling.batch-size:100}") int batchSize,
            @Value("${app.outbox.polling.max-retry:5}") int maxRetry,
            @Value("${app.outbox.polling.publish-timeout:6s}") Duration publishTimeout,
            @Value("${app.outbox.polling.cycle-timeout:4m}") Duration cycleTimeout,
            // 공유 DB 전환기 소유권 분리: 이 앱이 발행할 aggregateType allowlist (root=ORDER,PAYMENT / product=PRODUCT)
            @Value("${app.outbox.polling.aggregate-types}") List<String> aggregateTypes) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.slackPort = slackPort;
        this.aggregateTypes = requireNonEmpty(aggregateTypes);
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
        Gauge.builder("outbox.backlog", outboxEventRepository, repo -> repo.countByStatusAndAggregateTypeIn(status, aggregateTypes))
                .description("발행 대기/소진 outbox 이벤트 수 (scrape 시점 집계)")
                .tag("status", tag)
                .register(meterRegistry);
    }

    public void pollAndPublish() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEvents(aggregateTypes, batchSize);
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

    private ProducerRecord<String, String> buildRecord(OutboxEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(
                event.getEventType(), null, event.getAggregateId(), event.getPayload());
        addHeaderIfPresent(record, KafkaTraceHeaders.TRACE_ID, event.getTraceId());
        addHeaderIfPresent(record, KafkaTraceHeaders.USER_ID, event.getUserId());
        return record;
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

    private static List<String> requireNonEmpty(List<String> aggregateTypes) {
        if (aggregateTypes == null || aggregateTypes.isEmpty()) {
            throw new IllegalArgumentException("app.outbox.polling.aggregate-types must be set");
        }
        return aggregateTypes;
    }

    private static Duration requirePositive(Duration value, String name) {
        if (value == null || value.toMillis() <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
