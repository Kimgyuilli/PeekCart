package com.peekcart.payment.infrastructure.kafka;

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
 * Payment 서비스의 Kafka 배선 (ADR-0011 §D2 · ADR-0012 D4).
 * <p><b>NewTopic(producer-owns-topic)</b>: Payment 는 자기가 발행하는 토픽 {@code payment.completed}·{@code payment.failed}·
 * {@code payment.requested}(각 {@code .dlq} 포함)의 {@link NewTopic} 만 소유한다. {@code order.*}·{@code product.*}·
 * {@code stock.reservation.result} 는 각 발행 서비스가 소유(ADR-0011 §토픽=발행 서비스 전속).
 * <p>consumer 측: 소비 실패 시 {@code topic.dlq} 로 발행 + {@link SlackPort}(:common) 알림.
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class PaymentKafkaConfig {

    private final SlackPort slackPort;

    // --- 발행 토픽(producer-owns-topic) ---
    @Bean
    public NewTopic paymentCompletedTopic() {
        return TopicBuilder.name("payment.completed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name("payment.failed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentRequestedTopic() {
        return TopicBuilder.name("payment.requested").partitions(3).replicas(1).build();
    }

    // --- 발행 토픽 DLQ ---
    @Bean
    public NewTopic paymentCompletedDlqTopic() {
        return TopicBuilder.name("payment.completed.dlq").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedDlqTopic() {
        return TopicBuilder.name("payment.failed.dlq").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentRequestedDlqTopic() {
        return TopicBuilder.name("payment.requested.dlq").partitions(1).replicas(1).build();
    }

    // --- Error Handler + DLQ ---
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
