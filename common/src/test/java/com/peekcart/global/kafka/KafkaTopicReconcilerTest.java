package com.peekcart.global.kafka;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.kafka.core.KafkaAdmin;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기동 뒤 토픽 재적용의 실패 처리 (ADR-0020 §D4-1, D-106).
 *
 * <p>재적용이 실제로 경합 토픽을 맞추는지는 브로커가 있어야 보이므로
 * {@code order-service} 의 {@code KafkaTopicConfigMechanismIntegrationTest} 생성 경합 절이 본다.
 * 여기서는 브로커가 없을 때의 동작만 고정한다.
 */
@DisplayName("KafkaTopicReconciler — 기동 뒤 재적용 (ADR-0020 D4-1)")
class KafkaTopicReconcilerTest {

    /**
     * 컨텍스트 초기화 밖에서 부른 {@link KafkaAdmin#initialize()} 는 브로커가 없으면
     * {@code IllegalStateException} 을 던진다. {@link ApplicationReadyEvent} 리스너가 그것을 흘리면
     * {@code SpringApplication.run} 이 기동 실패로 처리해 애플리케이션이 내려간다.
     * 기동 중 적용은 같은 실패를 로그만 남기고 넘어가므로, 재적용도 기동을 깨면 안 된다.
     */
    @Test
    @DisplayName("재적용 실패는 전파하지 않는다 — 브로커가 없다고 기동이 깨지면 안 된다")
    void failureDoesNotPropagate() {
        KafkaAdmin kafkaAdmin = mock(KafkaAdmin.class);
        when(kafkaAdmin.initialize()).thenThrow(new IllegalStateException("Could not configure topics"));

        assertThatCode(() -> new KafkaTopicReconciler(kafkaAdmin).onApplicationEvent(mock(ApplicationReadyEvent.class)))
                .doesNotThrowAnyException();
        verify(kafkaAdmin).initialize();
    }
}
