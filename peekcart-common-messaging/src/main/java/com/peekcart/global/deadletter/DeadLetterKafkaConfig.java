package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.FixedSequenceBackOff;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * DLQ 전용 Kafka 배선 (계획 ④-c-2a P6·P8).
 *
 * <p><b>기본 factory 를 쓰면 안 되는 이유</b>: 기본 {@code kafkaListenerContainerFactory} 는
 * {@code kafkaErrorHandler} 를 물고 있어 DLQ listener 가 실패하면 {@code topic.dlq.dlq} 로
 * 재귀 발행한다. 여기서는 DLQ 발행을 하지 않는 error handler 를 쓴다.
 *
 * <p><b>{@code ackAfterHandle=false} 가 핵심이다.</b> 기본값(true)이면 recoverer 실행 후 offset 이
 * 커밋돼 <b>원장에 쓰지 못한 DLQ 레코드가 영구 유실</b>된다. false 로 두면 커밋되지 않아
 * 재기동 시 같은 offset 부터 다시 읽는다 — 무유실.
 */
@Configuration
@RequiredArgsConstructor
public class DeadLetterKafkaConfig {

    private final DeadLetterContainerGuard containerGuard;

    /**
     * DLQ listener 전용 error handler.
     *
     * <p>재시도는 유한하다(1s → 5s → 30s). 소진되면 컨테이너를 정지하고 사람을 부른다 —
     * DB 장애를 무한 재시도로 덮으면 로그만 쌓이고, 커밋하면 유실된다(§2.6-C).
     */
    @Bean
    public CommonErrorHandler deadLetterErrorHandler() {
        DefaultErrorHandler handler = new DefaultErrorHandler(
                containerGuard::onRetriesExhausted,
                new FixedSequenceBackOff(1_000, 5_000, 30_000));
        // 무유실 — recoverer 가 돌아도 offset 을 커밋하지 않는다.
        handler.setAckAfterHandle(false);
        return handler;
    }

    /**
     * DLQ listener 전용 container factory.
     * {@code DeadLetterPublishingRecoverer} 를 배선하지 않는다 — {@code .dlq.dlq} 를 만들지 않기 위함이다.
     */
    @Bean("deadLetterKafkaListenerContainerFactory")
    public ConcurrentKafkaListenerContainerFactory<String, String> deadLetterKafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory,
            CommonErrorHandler deadLetterErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.RECORD);
        factory.setCommonErrorHandler(deadLetterErrorHandler);
        return factory;
    }
}
