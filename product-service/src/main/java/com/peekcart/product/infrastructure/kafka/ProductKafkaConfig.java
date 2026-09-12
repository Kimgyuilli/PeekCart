package com.peekcart.product.infrastructure.kafka;

import com.peekcart.global.kafka.FixedSequenceBackOff;
import com.peekcart.global.kafka.KafkaTopicConfigs;
import com.peekcart.global.replay.OriginalRecordReader;
import com.peekcart.global.kafka.MdcPayloadExtractor;
import com.peekcart.global.kafka.MdcRecordInterceptor;
import com.peekcart.global.port.SlackPort;
import com.peekcart.global.retention.IdempotencyRetentionProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Product 서비스의 Kafka 소비 배선 (ADR-0011 §D2 — producer/consumer factory 는 :common/auto-config,
 * 서비스는 listener container factory·error-handler 등 자기 소비 경로만 소유).
 * <p><b>NewTopic(producer-owns-topic, ADR-0011 §토픽=발행 서비스 전속 · ADR-0012 D4)</b>: Product 는 자기가 발행하는
 * {@code product.updated}·{@code stock.reservation.result}·{@code stock.compensation.requested}(각 {@code .dlq} 포함)의 {@link NewTopic} 을 소유한다.
 * Payment peel(PR-b)로 root app 이 소멸하기 전엔 root 가 전 토픽을 생성했으나, root 해체 후 자기 토픽 생성자가
 * 사라지므로 본 서비스가 떠안는다. 소비 실패 시 {@code topic.dlq} 로 발행 + {@link SlackPort}(:common) 알림.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ProductKafkaConfig {

    private final SlackPort slackPort;

    /**
     * 토픽 config 의 값 출처 (ADR-0020 §D4-1).
     * {@code retention.ms} 는 멱등 창 계산의 입력({@code floor.kafka-topic-retention})과
     * <b>같은 출처</b>에서 유도한다 — 두 곳에 따로 적으면 갈라진다.
     */
    private final IdempotencyRetentionProperties retentionProperties;

    private java.util.Map<String, String> businessConfigs() {
        return KafkaTopicConfigs.business(retentionProperties.getFloor().getKafkaTopicRetention(),
                retentionProperties.topicMessageTimestampBeforeMax());
    }

    private java.util.Map<String, String> dlqConfigs() {
        return KafkaTopicConfigs.dlq(retentionProperties.getFloor().getKafkaTopicRetention(),
                retentionProperties.topicMessageTimestampBeforeMax());
    }

    // --- 발행 토픽(producer-owns-topic) — Payment peel 로 root 소멸 후 자기 토픽 생성 책임 승계 ---
    @Bean
    public NewTopic productUpdatedTopic() {
        return TopicBuilder.name("product.updated").partitions(3).replicas(1).configs(businessConfigs()).build();
    }

    @Bean
    public NewTopic stockReservationResultTopic() {
        return TopicBuilder.name("stock.reservation.result").partitions(3).replicas(1).configs(businessConfigs()).build();
    }

    /**
     * 환불 보상 요청 (ADR-0018 D1). Product 가 PAID_BUT_UNRESERVED 를 감지하고 Payment 가 소비한다 —
     * 요청을 공용 토픽 1개로 합치면 producer 가 2개가 되어 소유가 모호해지므로 토픽을 나눈다.
     */
    @Bean
    public NewTopic stockCompensationRequestedTopic() {
        return TopicBuilder.name("stock.compensation.requested").partitions(3).replicas(1).configs(businessConfigs()).build();
    }

    @Bean
    public NewTopic productUpdatedDlqTopic() {
        return TopicBuilder.name("product.updated.dlq").partitions(1).replicas(1).configs(dlqConfigs()).build();
    }

    @Bean
    public NewTopic stockReservationResultDlqTopic() {
        return TopicBuilder.name("stock.reservation.result.dlq").partitions(1).replicas(1).configs(dlqConfigs()).build();
    }

    @Bean
    public NewTopic stockCompensationRequestedDlqTopic() {
        return TopicBuilder.name("stock.compensation.requested.dlq").partitions(1).replicas(1).configs(dlqConfigs()).build();
    }

    @Bean
    public CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer dlqRecoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, ex) -> new TopicPartition(record.topic() + ".dlq", -1)
        );
        dlqRecoverer.setFailIfSendResultIsError(true);

        return new DefaultErrorHandler((record, exception) -> {
            String message = String.format(
                    "[DLQ] topic=%s, partition=%d, offset=%d, exception=%s",
                    record.topic(), record.partition(), record.offset(),
                    exception.getMessage()
            );
            log.error(message, exception);
            dlqRecoverer.accept(record, exception);
            try {
                slackPort.send(message);
            } catch (Exception e) {
                log.warn("DLQ Slack 알림 발송 실패", e);
            }
        }, new FixedSequenceBackOff(1_000, 5_000, 30_000));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            CommonErrorHandler kafkaErrorHandler,
            MdcPayloadExtractor mdcPayloadExtractor) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(kafkaErrorHandler);
        factory.setRecordInterceptor(new MdcRecordInterceptor(mdcPayloadExtractor));
        return factory;
    }

    /**
     * DLQ replay 좌표 reader (구현 ④-c-2b-4a P18).
     *
     * <p><b>공통 모듈에서 자동 등록하지 않는다</b> — {@code @Component} 로 두면 Kafka 가 없는 서비스
     * (user-service)의 컨텍스트가 {@code KafkaAdmin} 부재로 깨진다. 원장을 가진 서비스만 등록한다.
     */
    @Bean
    public OriginalRecordReader originalRecordReader(KafkaAdmin kafkaAdmin,
                                                     ConsumerFactory<String, String> consumerFactory,
                                                     IdempotencyRetentionProperties retentionProperties) {
        return new OriginalRecordReader(kafkaAdmin, consumerFactory, retentionProperties);
    }
}
