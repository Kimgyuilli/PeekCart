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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ServiceTest
@DisplayName("OutboxPollingService 단위 테스트")
class OutboxPollingServiceTest {

    OutboxPollingService outboxPollingService;
    @Mock OutboxEventRepository outboxEventRepository;
    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock SlackPort slackPort;

    @BeforeEach
    void setUp() {
        org.slf4j.MDC.clear();
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

    private static OutboxEvent pendingEvent(String traceId, String userId) {
        return OutboxEvent.create("Order", "1", "order.created", traceId, userId, eventId -> "{}");
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

    private OutboxPollingService service(Duration publishTimeout, Duration cycleTimeout) {
        return new OutboxPollingService(outboxEventRepository, kafkaTemplate, slackPort,
                new SimpleMeterRegistry(), 100, 5, publishTimeout, cycleTimeout);
    }
}
