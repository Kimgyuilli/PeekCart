package com.peekcart.global.kafka;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.awaitility.core.ConditionTimeoutException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * 토픽 config 가 <b>실제 브로커에 적용되는 경로</b>를 검증한다 (ADR-0020 §D4-1 · 계획 V-P4-1~4).
 *
 * <p><b>왜 이 테스트가 필요한가</b>: {@code NewTopic} 에 config 를 선언하는 것만으로는
 * <b>이미 존재하는 토픽</b>이 바뀌지 않는다. {@code KafkaAdmin.modifyTopicConfigs} 의 기본값이
 * {@code false} 이기 때문이다. 로컬에서 새 토픽으로만 확인하면 "설정했다" 가
 * <b>신규 클러스터에서만 참인 채로</b> 통과한다 — 배포된 클러스터의 토픽은 전부 기존 토픽이다.
 *
 * <p>그래서 red 를 먼저 만든다: 같은 브로커에 {@code false} 로 한 번, {@code true} 로 한 번
 * 순차 적용해 <b>false 에서는 옛 값이 유지됨</b>을 단언한다. 그 단언이 깨지면 이 테스트가
 * 검증하려던 대비 자체가 성립하지 않으므로 즉시 실패시킨다.
 *
 * <p>컨테이너는 <b>클래스당 하나</b>이고 테스트마다 토픽 이름을 새로 만든다 —
 * 두 컨텍스트가 같은 브로커를 봐야 "기존 토픽" 이라는 조건이 성립한다.
 */
@Testcontainers
@DisplayName("토픽 config 적용 경로 — 신규/기존 토픽 · 미선언 config 처분 (ADR-0020 D4-1)")
class KafkaTopicConfigMechanismIntegrationTest {

    @Container
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    private static final Duration RETENTION = Duration.ofDays(7);
    private static final Duration BEFORE_MAX = Duration.ofDays(9);

    /** 계약 키 7종 (ADR-0020 §D4-1). 3개만 단언하면 segment/timestamp 미적용이 green 으로 통과한다. */
    private static final List<String> CONTRACT_KEYS = List.of(
            "retention.ms", "cleanup.policy", "retention.bytes",
            "segment.bytes", "segment.ms",
            "message.timestamp.type", "message.timestamp.before.max.ms");

    private Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
    }

    /** {@code NewTopic} 을 선언한 {@link KafkaAdmin} 을 기동해 브로커에 반영시킨다. */
    private void applyTopics(boolean modifyTopicConfigs, NewTopic... topics) {
        KafkaAdmin kafkaAdmin = new KafkaAdmin(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()));
        kafkaAdmin.setModifyTopicConfigs(modifyTopicConfigs);
        kafkaAdmin.setAutoCreate(false);
        kafkaAdmin.createOrModifyTopics(topics);
        for (NewTopic topic : topics) {
            awaitTopicVisible(topic.name());
        }
    }

    private Map<String, ConfigEntry> describe(String topic) throws Exception {
        try (Admin admin = admin()) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            return admin.describeConfigs(List.of(resource)).all().get()
                    .get(resource).entries().stream()
                    .collect(java.util.stream.Collectors.toMap(ConfigEntry::name, e -> e));
        }
    }

    /**
     * 토픽이 브로커 메타데이터에 보일 때까지 기다린다 (D-028).
     *
     * <p><b>왜 필요한가</b>: {@code createOrModifyTopics} 가 리턴해도 뒤이은 {@code describe} 가
     * 즉시 그 토픽을 보지는 못한다. 붐비는 CI shard 에서 그 창에 들어가 <b>셋업 단언</b>이
     * 깨졌다(PR #125 CI, order shard 418건 중 2건 — {@code UnknownTopicOrPartitionException}).
     *
     * <p>{@link #awaitConfig} 로는 닿지 않는다. 그쪽은 <b>한 칸 뒤</b>의 문제를 덮는다 —
     * "토픽은 있는데 값이 구값". {@code untilAsserted} 는 {@code AssertionError} 만 삼키므로
     * "토픽 자체가 없음" 은 관통한다.
     *
     * <p><b>Awaitility 의 {@code ignoreException} 을 쓰지 않는 이유</b>: {@code all().get()} 이
     * 던지는 것은 {@code UnknownTopicOrPartitionException} 이 아니라 그것을 {@code cause} 로
     * 감싼 {@code ExecutionException} 이고, {@code ignoreException} 은 {@code cause} 를 풀지
     * 않는다. {@code ignoreExceptions()} 로 넓히면 진짜 브로커 오류까지 삼켜 타임아웃으로
     * 뭉개진다. 그래서 {@code cause} 를 직접 보고 <b>그 예외만</b> 재시도한다.
     *
     * <p><b>전제에만 쓴다.</b> 토픽 존재에만 걸고 config 값에는 걸지 않는다 — D-019 와 같은 선.
     */
    private void awaitTopicVisible(String topic) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    try {
                        describe(topic);
                        return true;
                    } catch (ExecutionException e) {
                        if (e.getCause() instanceof UnknownTopicOrPartitionException) {
                            return false;
                        }
                        throw e;
                    }
                });
    }

    /**
     * 방금 쓴 config 가 {@code describe} 로 보일 때까지 기다린다 (D-021).
     *
     * <p><b>왜 필요한가</b>: `incrementalAlterConfigs(...).all().get()` 이나 `createOrModifyTopics` 가
     * 돌아왔다고 해서 뒤이은 describe 가 즉시 새 값을 주지는 않는다. 붐비는 CI shard 에서 구값을
     * 받아 <b>셋업 단언</b>이 깨졌다(PR #117 CI, order shard 22m39s — 동일 커밋 재실행은 green).
     *
     * <p><b>검증 대상이 아니라 전제에만 쓴다.</b> 계약 단언(modify 이후 값이 무엇이어야 하는가)을
     * 폴링으로 감싸면 "언젠가 맞으면 통과" 가 되어 하드닝이 풀린다 — D-019 와 같은 선.
     */
    private void awaitConfig(String topic, String key, String expected) {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(describe(topic).get(key).value()).isEqualTo(expected));
    }

    private NewTopic business(String name, Map<String, String> configs) {
        return TopicBuilder.name(name).partitions(1).replicas(1).configs(configs).build();
    }

    // ── D-028 셋업 대기 자체의 계약 ─────────────────────────────────────────────
    // awaitTopicVisible 은 다른 모든 테스트의 전제를 떠받치므로, 그것이 실제로 동작하는지를
    // 실패 주입으로 확인한다. 이 둘이 없으면 대기가 조용히 안 먹거나(cause 를 못 풀거나)
    // 모든 오류를 삼켜도 나머지가 green 으로 통과해 버린다.

    /**
     * 토픽이 끝내 안 보이면 대기는 타임아웃해야 한다.
     *
     * <p>catch 가 {@code cause} 를 풀지 못하면 {@code ExecutionException} 이 그대로 관통해
     * 타임아웃이 아닌 다른 예외로 떨어진다 — 그것이 곧 대기가 안 먹는다는 증거다.
     */
    @Test
    @DisplayName("D-028 대기 — 없는 토픽이면 cause 를 풀어 재시도하다 타임아웃한다")
    void awaitTopicVisibleRetriesWhileTopicAbsent() {
        assertThatThrownBy(() -> awaitTopicVisible("never-created-" + UUID.randomUUID()))
                .as("ExecutionException 이 관통하면 catch 가 cause 를 풀지 못한 것이다")
                .isInstanceOf(ConditionTimeoutException.class);
    }

    /**
     * 토픽 부재가 아닌 오류는 즉시 던져야 한다.
     *
     * <p>삼키면 진짜 브로커 오류가 10초 뒤 타임아웃으로 뭉개져 원인이 사라진다.
     * 그래서 {@code ignoreExceptions()} 로 넓히지 않았고, 그 선택을 여기서 고정한다.
     */
    @Test
    @DisplayName("D-028 대기 — 토픽 부재가 아닌 오류는 삼키지 않고 즉시 던진다")
    void awaitTopicVisibleRethrowsOtherErrors() {
        assertThatThrownBy(() -> awaitTopicVisible("invalid topic name!"))
                .as("타임아웃으로 떨어지면 대기가 관계없는 오류까지 삼킨 것이다")
                .isNotInstanceOf(ConditionTimeoutException.class);
    }

    // ── V-P4-1 [측정] 기준선 ────────────────────────────────────────────────────
    // 판정이 아니라 아래 두 테스트의 대조 기준이다. ADR §C1 이 "실효값은 Apache 기본 7일"
    // 이라고 적은 근거를 이미지 파일이 아니라 런타임 관측으로 승격시킨다.

    /**
     * 선언 없는 토픽의 브로커 기본값 — 7종 <b>전부</b>의 값과 정확한 {@code ConfigSource}.
     *
     * <p>ADR §C1 의 "실효값은 Apache 기본 7일" 을 이미지 파일 근거가 아니라
     * <b>런타임 관측</b>으로 승격시키고, V-P4-2/3 의 독립 대조표가 된다.
     * 4종만 적으면 {@code segment.*}·{@code message.timestamp.before.max.ms} 의 기준값이
     * 증적에서 빠져 "무엇이 바뀌었는지" 를 나중에 판정할 수 없다.
     */
    @Test
    @DisplayName("V-P4-1 기준선 — 업무·dlq 각각 7종의 값과 ConfigSource 를 전부 기록한다")
    void baselineIsBrokerDefault() throws Exception {
        for (String prefix : List.of("baseline-business-", "baseline-dlq-")) {
            String topic = prefix + UUID.randomUUID();
            applyTopics(false, TopicBuilder.name(topic).partitions(1).replicas(1).build());

            Map<String, ConfigEntry> configs = describe(topic);
            Map<String, String> expectedDefaults = Map.of(
                    "retention.ms", String.valueOf(Duration.ofDays(7).toMillis()),
                    "cleanup.policy", "delete",
                    "retention.bytes", "-1",
                    "segment.bytes", String.valueOf(1024L * 1024 * 1024),
                    "segment.ms", String.valueOf(Duration.ofDays(7).toMillis()),
                    "message.timestamp.type", "CreateTime",
                    "message.timestamp.before.max.ms", String.valueOf(Long.MAX_VALUE));

            for (String key : CONTRACT_KEYS) {
                assertThat(configs).containsKey(key);
                assertThat(configs.get(key).value())
                        .as("%s 의 브로커 기본값 (%s)", key, topic)
                        .isEqualTo(expectedDefaults.get(key));
                assertThat(configs.get(key).source())
                        .as("선언 없는 %s 는 DEFAULT_CONFIG 여야 한다 (%s)", key, topic)
                        .isEqualTo(ConfigEntry.ConfigSource.DEFAULT_CONFIG);
            }
        }
    }

    // ── V-P4-2 [변이] 신규 토픽 경로 ────────────────────────────────────────────

    @Test
    @DisplayName("V-P4-2 신규 토픽 — 7종 전부가 선언값 + DYNAMIC_TOPIC_CONFIG 로 적용된다")
    void newTopicGetsAllSevenDeclaredConfigs() throws Exception {
        String topic = "new-" + UUID.randomUUID();
        Map<String, String> declared = KafkaTopicConfigs.business(RETENTION, BEFORE_MAX);
        applyTopics(false, business(topic, declared));

        Map<String, ConfigEntry> actual = describe(topic);
        for (String key : CONTRACT_KEYS) {
            assertThat(actual.get(key).value())
                    .as("%s 가 선언값으로 적용되어야 한다", key)
                    .isEqualTo(declared.get(key));
            assertThat(actual.get(key).source())
                    .as("%s 는 토픽 동적 설정이어야 한다", key)
                    .isEqualTo(ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG);
        }
    }

    @Test
    @DisplayName("V-P4-2 변이 — 키를 하나 빼면 그 키만 기본값으로 남는다 (개별 단언이 필요한 이유)")
    void omittingOneKeyLeavesItAtDefault() throws Exception {
        for (String omitted : CONTRACT_KEYS) {
            String topic = "omit-" + UUID.randomUUID();
            Map<String, String> declared = new java.util.LinkedHashMap<>(
                    KafkaTopicConfigs.business(RETENTION, BEFORE_MAX));
            declared.remove(omitted);
            applyTopics(false, business(topic, declared));

            Map<String, ConfigEntry> actual = describe(topic);
            assertThat(actual.get(omitted).source())
                    .as("뺀 키 %s 는 DYNAMIC 이 아니어야 한다 — 3개만 단언하면 이 누락이 green 으로 통과한다", omitted)
                    .isNotEqualTo(ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG);
        }
    }

    // ── V-P4-3 [변이] 기존 토픽 경로 (핵심) ─────────────────────────────────────

    @Test
    @DisplayName("V-P4-3 기존 토픽 — modify=false 는 옛 값 유지(red 재현), true 라야 7종이 갱신된다")
    void existingTopicRequiresModifyTopicConfigs() throws Exception {
        String topic = "existing-" + UUID.randomUUID();

        // ① 옛 config 로 토픽 생성
        Map<String, String> old = Map.of(
                "retention.ms", String.valueOf(Duration.ofDays(1).toMillis()),
                "segment.bytes", String.valueOf(64L * 1024 * 1024));
        applyTopics(false, business(topic, old));
        awaitConfig(topic, "retention.ms", String.valueOf(Duration.ofDays(1).toMillis()));

        Map<String, String> declared = KafkaTopicConfigs.business(RETENTION, BEFORE_MAX);

        // ② modify=false 로 새 선언 적용 → 반영되지 않아야 한다 (이 단언이 red 대비의 근거)
        applyTopics(false, business(topic, declared));
        Map<String, ConfigEntry> afterFalse = describe(topic);
        assertThat(afterFalse.get("retention.ms").value())
                .as("modify-topic-configs=false 인데 값이 바뀌었다면 이 테스트의 대비 자체가 무효다")
                .isEqualTo(String.valueOf(Duration.ofDays(1).toMillis()));
        assertThat(afterFalse.get("segment.bytes").value())
                .isEqualTo(String.valueOf(64L * 1024 * 1024));

        // ③ modify=true → 7종 전부 갱신
        applyTopics(true, business(topic, declared));
        Map<String, ConfigEntry> afterTrue = describe(topic);
        for (String key : CONTRACT_KEYS) {
            assertThat(afterTrue.get(key).value())
                    .as("modify-topic-configs=true 이면 %s 가 갱신되어야 한다", key)
                    .isEqualTo(declared.get(key));
        }
    }

    // ── V-P4-4 [변이] 미선언 config 의 처분 — acceptance criterion ───────────────

    /**
     * {@code NewTopic} 에 <b>선언하지 않은</b> 기존 dynamic config 가 살아남는지 본다.
     *
     * <p>이것은 "관측해서 사실대로 적는다" 가 아니라 <b>합격 기준</b>이다. 지워진다면
     * 우리가 선언하지 않은 운영 설정이 배포 때마다 조용히 되돌려진다는 뜻이므로,
     * (a) 모든 dynamic config 를 선언적으로 소유하거나 (b) modify 전략을 바꾸기 전까지
     * 이 테스트는 red 로 남아야 한다.
     */
    @Test
    @DisplayName("V-P4-4 미선언 config 는 modify=true 에서도 보존된다 (acceptance)")
    void undeclaredDynamicConfigSurvivesModify() throws Exception {
        String topic = "undeclared-" + UUID.randomUUID();
        applyTopics(false, business(topic, KafkaTopicConfigs.business(RETENTION, BEFORE_MAX)));

        // 우리 계약에 없는 config 를 운영자가 dynamic 으로 심어 둔 상황
        String foreignKey = "max.message.bytes";
        String foreignValue = "1048576";
        try (Admin admin = admin()) {
            ConfigResource resource = new ConfigResource(ConfigResource.Type.TOPIC, topic);
            admin.incrementalAlterConfigs(Map.of(resource, List.of(
                    new AlterConfigOp(new ConfigEntry(foreignKey, foreignValue), AlterConfigOp.OpType.SET)
            ))).all().get();
        }
        awaitConfig(topic, foreignKey, foreignValue);

        // 계약 config 를 modify=true 로 다시 적용
        applyTopics(true, business(topic, KafkaTopicConfigs.business(RETENTION, BEFORE_MAX)));

        assertThat(describe(topic).get(foreignKey).value())
                .as("미선언 dynamic config 가 지워지면 배포마다 운영 설정이 조용히 되돌려진다 — "
                        + "그 경우 이 테스트는 red 로 남아야 하며 modify 전략을 재검토해야 한다")
                .isEqualTo(foreignValue);
    }
}
