package com.peekcart.global.config;

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

@Slf4j
@Configuration
@RequiredArgsConstructor
public class KafkaConfig {

    private final SlackPort slackPort;

    // --- 원본 토픽 ---

    @Bean
    public NewTopic orderCreatedTopic() {
        return TopicBuilder.name("order.created").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentCompletedTopic() {
        return TopicBuilder.name("payment.completed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedTopic() {
        return TopicBuilder.name("payment.failed").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic orderCancelledTopic() {
        return TopicBuilder.name("order.cancelled").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic stockReservationResultTopic() {
        return TopicBuilder.name("stock.reservation.result").partitions(3).replicas(1).build();
    }

    // --- DLQ 토픽 ---

    @Bean
    public NewTopic orderCreatedDlqTopic() {
        return TopicBuilder.name("order.created.dlq").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentCompletedDlqTopic() {
        return TopicBuilder.name("payment.completed.dlq").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic paymentFailedDlqTopic() {
        return TopicBuilder.name("payment.failed.dlq").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic orderCancelledDlqTopic() {
        return TopicBuilder.name("order.cancelled.dlq").partitions(1).replicas(1).build();
    }

    @Bean
    public NewTopic stockReservationResultDlqTopic() {
        return TopicBuilder.name("stock.reservation.result.dlq").partitions(1).replicas(1).build();
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
