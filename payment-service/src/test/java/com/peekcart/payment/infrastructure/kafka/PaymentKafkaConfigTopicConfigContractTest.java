package com.peekcart.payment.infrastructure.kafka;

import com.peekcart.global.kafka.KafkaTopicConfigs;
import com.peekcart.global.retention.IdempotencyRetentionProperties;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 이 서비스가 소유한 <b>모든</b> {@code NewTopic} 이 config 계약을 갖는지 (ADR-0020 §D4-1).
 *
 * <p>토픽을 새로 추가하면서 {@code .configs(...)} 를 빠뜨리는 것이 이 계약이 조용히 깨지는
 * 가장 흔한 경로다. 목록을 하드코딩하지 않고 <b>{@code @Bean NewTopic} 메서드를 리플렉션으로
 * 전수 조회</b>하므로, 새 토픽이 추가되면 자동으로 검사 대상이 된다.
 */
@DisplayName("PaymentKafkaConfig — 전 토픽 config 계약 (ADR-0020 D4-1)")
class PaymentKafkaConfigTopicConfigContractTest {

    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Duration BEFORE_MAX = Duration.ofDays(9);

    private static final List<String> CONTRACT_KEYS = List.of(
            "retention.ms", "cleanup.policy", "retention.bytes",
            "segment.bytes", "segment.ms",
            "message.timestamp.type", "message.timestamp.before.max.ms");

    /** 이 서비스가 발행하는 원본 토픽. 각각 {@code .dlq} 짝이 있어야 한다. */
    private static final List<String> ORIGIN_TOPICS = List.of("payment.approval-recovery.requested", "payment.approval-recovery.execute", "payment.completed", "payment.failed", "payment.requested", "payment.refunded");

    private PaymentKafkaConfig config() {
        IdempotencyRetentionProperties props = new IdempotencyRetentionProperties();
        props.setRetention(BEFORE_MAX);
        props.getFloor().setKafkaTopicRetention(RETENTION);
        return new PaymentKafkaConfig(mock(com.peekcart.global.port.SlackPort.class), props);
    }

    /**
     * {@code @Bean} 으로 선언된 {@link NewTopic} 을 수집한다.
     *
     * <p><b>이 수집기가 놓치는 형태</b>: 인자를 받는 {@code @Bean},
     * 다른 {@code @Configuration} 에 선언된 토픽, {@code KafkaAdmin.NewTopics} 묶음 선언.
     * 앞의 둘은 {@link #topicInventoryMatchesDeclaration()} 의 목록 대조가 잡고,
     * 마지막은 {@link #noBulkNewTopicsDeclaration()} 이 명시적으로 막는다 —
     * 그 형태로 토픽을 추가하면 config 없이도 회귀가 안 생기기 때문이다.
     */
    private List<NewTopic> allTopics() throws Exception {
        PaymentKafkaConfig config = config();
        List<NewTopic> topics = new ArrayList<>();
        for (Method m : PaymentKafkaConfig.class.getMethods()) {
            if (m.isAnnotationPresent(Bean.class)
                    && NewTopic.class.equals(m.getReturnType())
                    && m.getParameterCount() == 0) {
                m.setAccessible(true);
                topics.add((NewTopic) m.invoke(config));
            }
        }
        return topics;
    }

    @Test
    @DisplayName("NewTopics 묶음 선언을 쓰지 않는다 — 이 수집기를 우회해 config 누락이 조용히 통과한다")
    void noBulkNewTopicsDeclaration() {
        assertThat(java.util.Arrays.stream(PaymentKafkaConfig.class.getMethods())
                .filter(m -> m.isAnnotationPresent(Bean.class))
                .map(Method::getReturnType)
                .map(Class::getName))
                .noneMatch(n -> n.endsWith("KafkaAdmin$NewTopics"));
    }

    @Test
    @DisplayName("spring.kafka.admin.modify-topic-configs 가 base YAML 에 true 로 배선돼 있다")
    void modifyTopicConfigsIsWiredInBaseYaml() throws Exception {
        // 기존 토픽은 이 키 없이는 갱신되지 않는다(KafkaAdmin.modifyTopicConfigs 기본 false).
        // 메커니즘 테스트는 setter 를 직접 부르므로 이 키가 지워져도 green 이다 — 그래서 배선을 따로 본다.
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load("application.yml", new ClassPathResource("application.yml"));
        assertThat(sources).isNotEmpty();
        Object value = sources.stream()
                .map(src -> src.getProperty("spring.kafka.admin.modify-topic-configs"))
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
        assertThat(value)
                .as("이 키가 없거나 false 면 배포된 클러스터의 **기존** 토픽 config 가 영원히 갱신되지 않는다")
                .isNotNull();
        assertThat(String.valueOf(value)).isEqualTo("true");
    }

    @Test
    @DisplayName("발행 토픽과 .dlq 가 짝을 이루고, 그 목록이 실제 @Bean 과 일치한다")
    void topicInventoryMatchesDeclaration() throws Exception {
        List<String> expected = new ArrayList<>();
        ORIGIN_TOPICS.forEach(t -> { expected.add(t); expected.add(t + ".dlq"); });
        assertThat(allTopics().stream().map(NewTopic::name))
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("모든 토픽이 계약 키 7종을 갖는다 — 하나라도 빠지면 그 토픽만 브로커 기본값으로 남는다")
    void everyTopicCarriesAllContractKeys() throws Exception {
        for (NewTopic topic : allTopics()) {
            assertThat(topic.configs())
                    .as("%s 의 config", topic.name())
                    .containsOnlyKeys(CONTRACT_KEYS.toArray(new String[0]));
        }
    }

    @Test
    @DisplayName("업무/.dlq 가 각각 알맞은 프로필을 쓴다 — .dlq 에 업무 크기를 주면 용량 산정이 깨진다")
    void topicsUseMatchingProfile() throws Exception {
        Map<String, String> business = KafkaTopicConfigs.business(RETENTION, BEFORE_MAX);
        Map<String, String> dlq = KafkaTopicConfigs.dlq(RETENTION, BEFORE_MAX);
        for (NewTopic topic : allTopics()) {
            assertThat(topic.configs())
                    .as("%s", topic.name())
                    .isEqualTo(topic.name().endsWith(".dlq") ? dlq : business);
        }
    }

    @Test
    @DisplayName("retention.ms 는 floor.kafka-topic-retention 에서 유도된다 — 리터럴이면 floor 변경 시 갈라진다")
    void retentionMsDerivesFromFloor() throws Exception {
        for (NewTopic topic : allTopics()) {
            assertThat(topic.configs().get("retention.ms"))
                    .as("%s", topic.name())
                    .isEqualTo(String.valueOf(RETENTION.toMillis()));
        }
    }
}
