package com.peekcart.product.infrastructure.kafka;

import com.peekcart.global.kafka.FixedSequenceBackOff;
import com.peekcart.global.kafka.MdcPayloadExtractor;
import com.peekcart.global.kafka.MdcRecordInterceptor;
import com.peekcart.global.port.SlackPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * Product 서비스의 Kafka 소비 배선 (ADR-0011 §D2 — producer/consumer factory 는 :common/auto-config,
 * 서비스는 listener container factory·error-handler 등 자기 소비 경로만 소유).
 * <p><b>NewTopic(producer-owns-topic, ADR-0011 §토픽=발행 서비스 전속 · ADR-0012 D4)</b>: Product 는 자기가 발행하는
 * {@code product.updated}·{@code stock.reservation.result}(각 {@code .dlq} 포함)의 {@link NewTopic} 을 소유한다.
 * Payment peel(PR-b)로 root app 이 소멸하기 전엔 root 가 전 토픽을 생성했으나, root 해체 후 자기 토픽 생성자가
 * 사라지므로 본 서비스가 떠안는다. 소비 실패 시 {@code topic.dlq} 로 발행 + {@link SlackPort}(:common) 알림.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ProductKafkaConfig {

    private final SlackPort slackPort;

    // --- 발행 토픽(producer-owns-topic) — Payment peel 로 root 소멸 후 자기 토픽 생성 책임 승계 ---
    @Bean
    public NewTopic productUpdatedTopic() {
        return TopicBuilder.name("product.updated").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic stockReservationResultTopic() {
        return TopicBuilder.name("stock.reservation.result").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic productUpdatedDlqTopic() {
        return TopicBuilder.name("product.updated.dlq").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic stockReservationResultDlqTopic() {
        return TopicBuilder.name("stock.reservation.result.dlq").partitions(1).replicas(1).build();
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
}
