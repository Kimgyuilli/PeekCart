package com.peekcart.global.outbox;

import com.peekcart.global.kafka.KafkaTraceHeaders;
import com.peekcart.global.port.SlackPort;
import com.peekcart.support.ServiceTest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ServiceTest
@DisplayName("OutboxPollingService 단위 테스트")
class OutboxPollingServiceTest {

    private static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong();

    OutboxPollingService outboxPollingService;
    SimpleMeterRegistry meterRegistry;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock SlackPort slackPort;

    @BeforeEach
    void setUp() {
        org.slf4j.MDC.clear();
        meterRegistry = new SimpleMeterRegistry();
        outboxPollingService = service(Duration.ofSeconds(6), Duration.ofMinutes(4));
    }

    @Test
    @DisplayName("Slack 알림 실패 시에도 FAILED 상태 저장이 수행되고 예외가 전파되지 않는다")
    void slackFailureIsIsolated() {
        OutboxEvent event = retryableEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willThrow(new RuntimeException("Kafka down"));
        willThrow(new RuntimeException("Slack down")).given(slackPort).send(any(String.class));

        assertThatCode(() -> outboxPollingService.pollAndPublish()).doesNotThrowAnyException();

        ArgumentCaptor<OutboxEvent> savedCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getStatus()).isEqualTo(OutboxEventStatus.FAILED);
        assertThat(savedCaptor.getValue().getRetryCount()).isEqualTo(5);
        verify(slackPort, times(1)).send(any(String.class));
    }

    @Test
    @DisplayName("MAX_RETRY 도달 시 Slack 알림이 정상 발송되면 FAILED 상태로 저장된다")
    void slackNotifiedOnMaxRetryReached() {
        OutboxEvent event = retryableEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willThrow(new RuntimeException("Kafka down"));

        outboxPollingService.pollAndPublish();

        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(slackPort).send(msgCaptor.capture());
        assertThat(msgCaptor.getValue()).contains("[Outbox FAILED]", event.getEventId());
        verify(outboxEventRepository).save(any(OutboxEvent.class));
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.FAILED);
    }

    @Test
    @DisplayName("Kafka 발행 실패 시 outbox.publish result=failure timer 가 증가하고 success 는 기록되지 않는다 (D-014/L-009)")
    void publishFailureRecordsFailureTimer() {
        OutboxEvent event = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willThrow(new RuntimeException("Kafka down"));

        outboxPollingService.pollAndPublish();

        assertThat(publishTimerCount("failure")).isEqualTo(1);
        assertThat(publishTimerCount("success")).isZero();
    }

    @Test
    @DisplayName("trace_id / user_id 가 set 된 OutboxEvent 발행 시 ProducerRecord 헤더에 두 키 모두 포함된다")
    void producerRecordCarriesTraceHeadersWhenSet() throws Exception {
        OutboxEvent event = pendingEvent("trace-abc", "42");
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        outboxPollingService.pollAndPublish();

        ArgumentCaptor<ProducerRecord<String, String>> recCaptor = recordCaptor();
        verify(kafkaTemplate).send(recCaptor.capture());
        ProducerRecord<String, String> sent = recCaptor.getValue();
        assertThat(headerValue(sent, KafkaTraceHeaders.TRACE_ID)).isEqualTo("trace-abc");
        assertThat(headerValue(sent, KafkaTraceHeaders.USER_ID)).isEqualTo("42");
    }

    @Test
    @DisplayName("trace_id / user_id 가 둘 다 null 이면 ProducerRecord 헤더에 두 키 모두 미주입된다")
    void producerRecordOmitsTraceHeadersWhenNull() {
        OutboxEvent event = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        outboxPollingService.pollAndPublish();

        ArgumentCaptor<ProducerRecord<String, String>> recCaptor = recordCaptor();
        verify(kafkaTemplate).send(recCaptor.capture());
        ProducerRecord<String, String> sent = recCaptor.getValue();
        assertThat(sent.headers().lastHeader(KafkaTraceHeaders.TRACE_ID)).isNull();
        assertThat(sent.headers().lastHeader(KafkaTraceHeaders.USER_ID)).isNull();
    }

    @Test
    @DisplayName("trace_id / user_id 가 빈 문자열이면 ProducerRecord 헤더에 두 키 모두 미주입된다 (ADR-0008 blank 정책)")
    void producerRecordOmitsTraceHeadersWhenBlank() {
        OutboxEvent event = pendingEvent("", "  ");
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        outboxPollingService.pollAndPublish();

        ArgumentCaptor<ProducerRecord<String, String>> recCaptor = recordCaptor();
        verify(kafkaTemplate).send(recCaptor.capture());
        ProducerRecord<String, String> sent = recCaptor.getValue();
        assertThat(sent.headers().lastHeader(KafkaTraceHeaders.TRACE_ID)).isNull();
        assertThat(sent.headers().lastHeader(KafkaTraceHeaders.USER_ID)).isNull();
    }

    @Test
    @DisplayName("Kafka 발행 future 가 완료되지 않으면 publish-timeout 이후 retryCount 를 증가시키고 저장한다")
    void publishTimeoutIncrementsRetryAndPersistsEvent() {
        outboxPollingService = service(Duration.ofMillis(10), Duration.ofSeconds(1));
        OutboxEvent event = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(new CompletableFuture<>());

        outboxPollingService.pollAndPublish();

        assertThat(event.getRetryCount()).isEqualTo(1);
        assertThat(event.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        verify(outboxEventRepository).save(event);
        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    @Test
    @DisplayName("cycle-timeout 도달 시 잔여 이벤트는 발행하지 않고 다음 폴링 사이클로 이월한다")
    void cycleTimeoutStopsBeforePublishingRemainingEvents() {
        outboxPollingService = service(Duration.ofMillis(10), Duration.ofMillis(5));
        OutboxEvent first = pendingEvent(null, null);
        OutboxEvent remaining = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(first, remaining));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(new CompletableFuture<>());

        outboxPollingService.pollAndPublish();

        assertThat(first.getRetryCount()).isEqualTo(1);
        assertThat(remaining.getRetryCount()).isZero();
        assertThat(remaining.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(outboxEventRepository).save(first);
        verify(outboxEventRepository, never()).save(remaining);
    }

    @Test
    @DisplayName("ack 받은 이벤트는 개별 save 없이 사이클 끝에 markPublished 한 번으로 기록된다 (ADR-0049 D1)")
    void publishedEventsAreRecordedOnceAtCycleEnd() {
        OutboxEvent e1 = pendingEvent(null, null);
        OutboxEvent e2 = pendingEvent(null, null);
        OutboxEvent e3 = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(e1, e2, e3));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(CompletableFuture.completedFuture(null));
        given(outboxEventRepository.markPublished(any(), any())).willReturn(3);

        outboxPollingService.pollAndPublish();

        verify(outboxEventRepository, times(1)).markPublished(eq(List.of(e1.getId(), e2.getId(), e3.getId())), any());
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("성공·실패가 섞이면 성공분만 묶어 기록하고 실패분은 retry 를 개별 저장한다 (ADR-0049 D2)")
    void mixedCycleRecordsSuccessesInBulkAndFailuresIndividually() {
        OutboxEvent ok1 = pendingEvent(null, null);
        OutboxEvent failed = pendingEvent(null, null);
        OutboxEvent ok2 = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(ok1, failed, ok2));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.completedFuture(null))
                .willThrow(new RuntimeException("Kafka down"))
                .willReturn(CompletableFuture.completedFuture(null));

        outboxPollingService.pollAndPublish();

        verify(outboxEventRepository, times(1)).markPublished(eq(List.of(ok1.getId(), ok2.getId())), any());
        verify(outboxEventRepository, times(1)).save(failed);
        assertThat(failed.getRetryCount()).isEqualTo(1);
        assertThat(failed.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
    }

    @Test
    @DisplayName("cycle-timeout 으로 끊기면 끊기기 전 ack 분만 기록한다")
    void cycleTimeoutRecordsOnlyEventsAckedBeforeBreak() {
        outboxPollingService = service(Duration.ofSeconds(6), Duration.ofMillis(50));
        OutboxEvent first = pendingEvent(null, null);
        OutboxEvent remaining = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(first, remaining));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willAnswer(invocation -> {
            Thread.sleep(80);
            return CompletableFuture.completedFuture(null);
        });

        outboxPollingService.pollAndPublish();

        verify(kafkaTemplate, times(1)).send(any(ProducerRecord.class));
        verify(outboxEventRepository, times(1)).markPublished(eq(List.of(first.getId())), any());
    }

    @Test
    @DisplayName("interrupt 로 끊기면 그 전 ack 분을 기록하고 interrupt 플래그를 유지한다")
    void interruptRecordsEventsAckedBeforeBreak() throws Exception {
        OutboxEvent first = pendingEvent(null, null);
        OutboxEvent interrupted = pendingEvent(null, null);
        OutboxEvent remaining = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(first, interrupted, remaining));
        @SuppressWarnings("unchecked")
        CompletableFuture<org.springframework.kafka.support.SendResult<String, String>> interruptedFuture =
                org.mockito.Mockito.mock(CompletableFuture.class);
        given(interruptedFuture.get(anyLong(), any())).willThrow(new InterruptedException("stop"));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.completedFuture(null))
                .willReturn(interruptedFuture);

        try {
            outboxPollingService.pollAndPublish();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }

        verify(kafkaTemplate, times(2)).send(any(ProducerRecord.class));
        verify(outboxEventRepository, times(1)).markPublished(eq(List.of(first.getId())), any());
        verify(outboxEventRepository, times(1)).save(interrupted);
    }

    @Test
    @DisplayName("묶음 기록이 실패하면 예외가 사이클 밖으로 나간다 — 삼키면 ack 분이 PENDING 인 채 실패가 숨는다 (ADR-0049 D3)")
    void bulkRecordFailurePropagates() {
        OutboxEvent event = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(event));
        given(kafkaTemplate.send(any(ProducerRecord.class))).willReturn(CompletableFuture.completedFuture(null));
        given(outboxEventRepository.markPublished(any(), any())).willThrow(new RuntimeException("DB down"));

        assertThatThrownBy(() -> outboxPollingService.pollAndPublish())
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB down");
    }

    @Test
    @DisplayName("실패 경로 저장 예외와 묶음 기록 예외가 겹치면 원래 예외를 던지고 기록 예외는 suppressed 로 남긴다")
    void failurePathExceptionKeepsBulkRecordFailureAsSuppressed() {
        OutboxEvent ok = pendingEvent(null, null);
        OutboxEvent failed = pendingEvent(null, null);
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of(ok, failed));
        given(kafkaTemplate.send(any(ProducerRecord.class)))
                .willReturn(CompletableFuture.completedFuture(null))
                .willThrow(new RuntimeException("Kafka down"));
        willThrow(new RuntimeException("retry save failed")).given(outboxEventRepository).save(failed);
        given(outboxEventRepository.markPublished(any(), any())).willThrow(new RuntimeException("bulk failed"));

        assertThatThrownBy(() -> outboxPollingService.pollAndPublish())
                .hasMessage("retry save failed")
                .satisfies(e -> assertThat(e.getSuppressed()).extracting(Throwable::getMessage)
                        .containsExactly("bulk failed"));
        verify(outboxEventRepository, times(1)).markPublished(eq(List.of(ok.getId())), any());
    }

    @Test
    @DisplayName("ack 가 하나도 없는 사이클은 markPublished 를 부르지 않는다")
    void noAckSkipsBulkRecord() {
        given(outboxEventRepository.findPendingEvents(anyInt())).willReturn(List.of());

        outboxPollingService.pollAndPublish();

        verify(outboxEventRepository, never()).markPublished(any(), any());
    }

    private static OutboxEvent pendingEvent(String traceId, String userId) {
        OutboxEvent event = OutboxEvent.create("Order", "1", "order.created", traceId, userId, eventId -> "{}");
        ReflectionTestUtils.setField(event, "id", NEXT_ID.incrementAndGet());
        return event;
    }

    private static OutboxEvent retryableEvent(String traceId, String userId) {
        OutboxEvent event = pendingEvent(traceId, userId);
        // MAX_RETRY=5. 다음 폴링에서 Kafka 실패 → retryCount 5 도달 → markFailed + Slack 알림 경로 진입
        ReflectionTestUtils.setField(event, "retryCount", 4);
        return event;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static ArgumentCaptor<ProducerRecord<String, String>> recordCaptor() {
        return ArgumentCaptor.forClass((Class) ProducerRecord.class);
    }

    private static String headerValue(ProducerRecord<String, String> record, String key) {
        var header = record.headers().lastHeader(key);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private long publishTimerCount(String result) {
        var timer = meterRegistry.find("outbox.publish").tag("result", result).timer();
        return timer == null ? 0 : timer.count();
    }

    private OutboxPollingService service(Duration publishTimeout, Duration cycleTimeout) {
        // DB-per-service: 자기 스키마의 PENDING 후보를 batchSize 만큼 읽고 publish/cycle timeout 안에서 발행한다.
        return new OutboxPollingService(outboxEventRepository, kafkaTemplate, slackPort,
                new com.fasterxml.jackson.databind.ObjectMapper(),
                meterRegistry, 100, 5, publishTimeout, cycleTimeout);
    }
}
