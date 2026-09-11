package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.global.kafka.PayloadDigest;
import com.peekcart.global.port.SlackPort;
import com.peekcart.support.AbstractIntegrationTest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.search.MeterNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

/**
 * replay 재실패의 <b>원자 상관</b>과 <b>재개방</b> 회귀 (구현 ④-c-2b-3b P15·P16 · ADR-0021 §D1).
 *
 * <p><b>이 테스트의 성질은 하나다</b>: 대조 predicate 를 <b>하나 제거하면 정확히 그 행만</b> red 다.
 * 그래서 {@code axisXxx} 테스트들은 각각 <b>정확히 한 입력만</b> 바꾼다 — 두 축을 동시에 흔들면
 * 한 predicate 를 지워도 다른 predicate 가 잡아 <b>변이가 관측되지 않는다</b>.
 *
 * <p><b>음성 기대값을 "행이 생겼다" 로만 쓰지 않는다.</b> 집계 쿼리는 전환 호환성 때문에
 * {@code root_record_id IS NULL} 도 root 로 세므로, 상관 실패 분기에서 {@code assignSelfRoot()} 를
 * 빠뜨려도 행 존재·backlog 단언은 전부 green 이고 "root 행은 자기 id 를 가진다" 계약만 조용히 깨진다.
 * 그래서 <b>{@code root_record_id == 자기 id}</b> 를 음성 공통 단언에 넣는다.
 *
 * <p><b>앵커는 fixture 로 심는다</b> — 앵커를 쓰는 실제 주체(replay 진입점)는 ④-c-2b-4 P21 이고
 * 아직 없다. 진입점→발행→재실패→상관 전 구간을 한 번 도는 것은 V-35 가 2b-4 에서 맡는다.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(DlqReplayCorrelationIntegrationTest.SlackRecorderConfig.class)
@DisplayName("DLQ replay 재실패 상관·재개방")
class DlqReplayCorrelationIntegrationTest extends AbstractIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("peekcart_test");

    @Container
    @ServiceConnection(name = "redis")
    static GenericContainer<?> redis = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer("apache/kafka:3.8.1");

    /**
     * Slack 문구를 <b>종류별로</b> 세기 위한 스텁. no-op mock 으로는 "상관된 자식에 신규 미결 알림이
     * 나갔다" 를 볼 수 없다 — 그게 P15-g-2 가 막으려는 것이다.
     */
    /** afterCommit callback 을 실패시키기 위한 스위치. 전파되면 이미 커밋된 이벤트가 재처리된다. */
    static final class FailingSlack {
        static volatile boolean armed = false;
    }

    @TestConfiguration
    static class SlackRecorderConfig {
        static final List<String> MESSAGES = new CopyOnWriteArrayList<>();

        @Bean
        SlackPort slackPort() {
            return message -> {
                if (FailingSlack.armed) {
                    // afterCommit callback 안에서 터진다. 전파되면 이미 커밋된 이벤트를 listener 가
                    // 재처리한다 — ④-d-1 3R #2 의 전례라 격리 여부를 실제로 시험한다.
                    throw new IllegalStateException("Slack 발송 실패 주입");
                }
                MESSAGES.add(message);
            };
        }
    }

    @Autowired DeadLetterRecorder recorder;
    /**
     * 로케이터 반환 <b>직후</b>에 개입하기 위한 seam (V-19m · V-15c).
     *
     * <p>이 경합은 fixture 를 미리 바꿔서는 재현되지 않는다 — recorder 진입 전에 상태를 바꾸면
     * 단계 1(로케이터)과 단계 2(잠금)가 <b>둘 다 바뀐 값을 보게 되어</b> 단계 3·5 의 변이가 관측되지 않는다.
     * 반드시 "로케이터는 옛 값을 반환했는데 그 뒤 상태가 바뀐" 창을 만들어야 한다.
     */
    @MockitoSpyBean DeadLetterRecordJpaRepository repository;
    @Autowired DeadLetterEndpoint endpoint;
    @Autowired DeadLetterMaintenanceScheduler scheduler;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    private static final String TOPIC = "payment.completed";
    private static final String GROUP = "order-svc-payment-completed-group";
    private static final String OWNER = "order";
    private static final String ATTEMPT = "11111111-1111-4111-8111-111111111111";
    private static final long TS = 1_757_000_000_000L;
    private static final String KEY = "order-42";
    private static final String PAYLOAD = "{\"eventId\":\"evt-1\",\"orderId\":42}";

    @BeforeEach
    void setUp() {
        cleanDatabase();
        repository.deleteAll();
        SlackRecorderConfig.MESSAGES.clear();
        meterRegistry.clear();
        // spy stub 을 남기면 다음 테스트가 엉뚱한 개입을 받는다.
        reset(repository);
    }

    // ================= 양성 대조군 =================

    @Nested
    @DisplayName("양성 — 전 축이 일치하면 원래 사건에 붙는다")
    class Positive {

        @Test
        @DisplayName("V-19a: 자식이 root 에 연결되고 backlog 는 1 그대로다")
        void correlatesWhenEveryAxisMatches() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);

            recorder.record(replayOrigin().build());

            DeadLetterRecord child = childRow(root);
            assertThat(child.getRootRecordId()).isEqualTo(root.getId());
            assertThat(child.getRootRecordId()).isNotEqualTo(child.getId());
            assertThat(repository.countUnresolved()).isEqualTo(1);
            assertThat(correlationCount("correlated", "none")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("V-19n: key 가 자식·root 양쪽 NULL 이어도 상관된다 — null-safe 를 equals 로 바꾸면 red")
        void correlatesWhenKeyNullOnBothSides() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD, r -> r.key(null));

            recorder.record(replayOrigin().key(null).build());

            assertThat(childRow(root).getRootRecordId()).isEqualTo(root.getId());
            assertThat(correlationCount("correlated", "none")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("V-19o: digest 가 양쪽 NULL 이어도 상관된다 (ADR-0021 §D2 계약 고정)")
        void correlatesWhenDigestNullOnBothSides() {
            // **fixture 전용 계약 고정이다.** 운영 경로에서는 도달할 수 없다 — ADR-0020 §D5-2 가
            // event_id IS NULL 을 replay 금지축으로 정하고, outbox_events.payload 는 TEXT NOT NULL 이다.
            // ADR-0021 §D2 가 "양쪽 null 이면 일치" 로 결정했으므로 그 결정을 코드에 고정해 둔다.
            // **root 의 eventId 도 null 로 맞춘다.** payload=null 이면 자식 eventId 는 추출되지 않아 null 인데
            // root 만 'evt-1' 이면 축 6(fingerprint)까지 어긋나 이 테스트가 digest 가 아닌 다른 축을 보게 된다.
            DeadLetterRecord root = seedRootWithAnchor(null, r -> r.digest(null).eventId(null));

            recorder.record(replayOrigin().payload(null).build());

            assertThat(childRow(root).getRootRecordId()).isEqualTo(root.getId());
        }
    }

    // ================= 단일 equality 음성 =================

    @Nested
    @DisplayName("음성 — 축 하나만 어긋나도 독립 root 다")
    class SingleAxisMismatch {

        @Test
        @DisplayName("V-19b: attempt-id 로 root 를 못 찾으면 상관을 시도하지 않는다 (로케이터 탐색 실패)")
        void axisAttemptNotFound() {
            seedRootWithAnchor(PAYLOAD);

            recorder.record(replayOrigin().attempt("22222222-2222-4222-8222-222222222222").build());

            assertIndependent("attempt_not_found");
        }

        @Test
        @DisplayName("V-19c: owner 헤더가 이 서비스가 아니면 상관하지 않는다")
        void axisOwner() {
            seedRootWithAnchor(PAYLOAD);

            recorder.record(replayOrigin().owner("payment").build());

            assertIndependent("owner_mismatch");
        }

        @Test
        @DisplayName("V-19d: 실제 DLT group 만 다르면 독립 root — 오류가 아니라 정상 경로다 (C-28)")
        void axisGroupActual() {
            // replay 는 업무 토픽에 실리므로 그 토픽을 구독하는 **모든** group 이 다시 소비한다.
            // 표적이 아닌 group 의 실패는 다른 사건이고, 독립 root 가 맞다.
            seedRootWithAnchor(PAYLOAD);

            recorder.record(replayOrigin().actualGroup("order-svc-other-group").build());

            assertIndependent("group_mismatch");
        }

        @Test
        @DisplayName("V-19d2: root 앵커의 group 만 다르면 독립 root — 3자 대조의 두 번째 equality")
        void axisGroupAnchor() {
            // 축 3 은 독립 equality 가 둘이다(실제 group == 헤더, 헤더 == root 앵커).
            // 한 행으로 묶으면 어느 값을 바꾸느냐에 따라 equality 하나를 지워도 green 이 된다.
            seedRootWithAnchor(PAYLOAD, r -> r.anchorGroup("order-svc-stale-group"));

            recorder.record(replayOrigin().build());

            assertIndependent("group_mismatch");
        }

        @Test
        @DisplayName("V-19e: root-id 헤더가 다르면 독립 root")
        void axisRootId() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);

            recorder.record(replayOrigin().rootId(root.getId() + 9999).build());

            assertIndependent("root_id_mismatch");
        }

        @Test
        @DisplayName("V-19f: topic 이 다르면 독립 root — destination 대조를 겸한다")
        void axisTopic() {
            seedRootWithAnchor(PAYLOAD);

            recorder.record(replayOrigin().topic("payment.failed").build());

            assertIndependent("topic_mismatch");
        }

        @Test
        @DisplayName("V-19g: root 의 event_id 만 다르면 독립 root — payload 는 건드리지 않는다")
        void axisEventId() {
            // **payload 를 바꾸면 안 된다.** 자식 eventId 는 payload 에서 추출되므로 payload 를 변조하면
            // digest(축 9)까지 함께 어긋나고, eventId predicate 를 지워도 digest 가 잡아 false-green 이 된다.
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD, r -> r.eventId("evt-other"));

            // 사전 조건: 이 fixture 는 digest 를 어긋내지 않았다.
            assertThat(root.getLastReplayPayloadDigest()).isEqualTo(PayloadDigest.sha256Hex(PAYLOAD));

            recorder.record(replayOrigin().build());

            assertIndependent("fingerprint_mismatch");
        }

        @Test
        @DisplayName("V-19h: key 가 값↔NULL 로 어긋나면 독립 root (양방향)")
        void axisKey() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD, r -> r.key(null));
            recorder.record(replayOrigin().build());
            assertIndependent("fingerprint_mismatch");

            setUp();
            seedRootWithAnchor(PAYLOAD);
            recorder.record(replayOrigin().key(null).build());
            assertIndependent("fingerprint_mismatch");
        }

        @Test
        @DisplayName("V-19i: original timestamp 가 다르면 독립 root")
        void axisTimestamp() {
            seedRootWithAnchor(PAYLOAD, r -> r.timestamp(TS + 1));

            recorder.record(replayOrigin().build());

            assertIndependent("fingerprint_mismatch");
        }

        @Test
        @DisplayName("V-19j: eventId·key·ts 가 같아도 payload 가 변조되면 독립 root — 조작 경계")
        void axisDigest() {
            // ADR §D5-4 의 조작 경계. 좌표 대조를 전부 통과하는 변조 payload 가 남의 사건에 붙고
            // 종결된 root 를 재개방하는 것을 digest 만이 막는다.
            String tampered = "{\"eventId\":\"evt-1\",\"orderId\":42,\"amount\":999999}";
            seedRootWithAnchor(PAYLOAD);

            recorder.record(replayOrigin().payload(tampered).build());

            assertIndependent("digest_mismatch");
        }
    }

    // ================= 시나리오 음성 =================

    @Nested
    @DisplayName("시나리오 — 단일축이 아닌 경우")
    class Scenarios {

        @Test
        @DisplayName("V-19k: 타 서비스 원장의 attempt-id 재사용 (축 1+2 동시) — 독립 root")
        void foreignLedgerAttempt() {
            seedRootWithAnchor(PAYLOAD);

            recorder.record(replayOrigin().owner("payment").build());

            assertIndependent("owner_mismatch");
        }

        @Test
        @DisplayName("V-19l: replay 가 아닌 최초 실패는 상관 경로를 타지 않는다 — 회귀 방지")
        void firstFailureIsUntouched() {
            recorder.record(plainOrigin());

            assertIndependent("no_attempt_header");
        }

        @Test
        @DisplayName("V-19m: 로케이터 반환 직후 root 의 attempt 가 바뀌면 독립 root (TOCTOU)")
        void attemptChangedAfterLocatorRead() {
            // 잠금 **후** 대조(단계 3)가 이 창을 닫는다. 그 비교를 제거하면 이 행만 red 다 —
            // 로케이터(단계 1)는 이미 옛 값으로 root 를 찾아낸 뒤이기 때문이다.
            //
            // **fixture 를 미리 바꾸면 이 경합이 재현되지 않는다**: recorder 진입 전에 attempt 를 바꾸면
            // 로케이터가 애초에 root 를 못 찾아 attempt_not_found 로 끝나고, 단계 3 의 비교는 타지도 않는다.
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);
            interfereAfterLocator(root.getId(), () -> jdbcTemplate.update(
                    "UPDATE dead_letter_records SET last_replay_attempt_id = ? WHERE id = ?",
                    "33333333-3333-4333-8333-333333333333", root.getId()));

            recorder.record(replayOrigin().build());

            assertIndependent("attempt_changed");
        }
    }

    // ================= 상태 전이 =================

    @Nested
    @DisplayName("재개방 — 사람이 닫은 것을 시스템이 되돌리는 유일한 경로")
    class Reopen {

        @Test
        @DisplayName("V-15: RESOLVED root 에 늦은 자식이 붙으면 재개방된다 — resolved_at 은 남는다")
        void reopensResolvedRoot() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);
            endpoint.transition(root.getId(), "resolve", "ops", "주문 상태가 CONFIRMED 임을 조회로 확인");
            SlackRecorderConfig.MESSAGES.clear();

            recorder.record(replayOrigin().build());

            // **별도 트랜잭션에서 재조회한다** — 이 단언이 없으면 detach 결함(재개방이 UPDATE 되지 않음)이 green 이다.
            DeadLetterRecord reopened = freshlyLoaded(root.getId());
            assertThat(reopened.statusValue()).isEqualTo(DeadLetterStatus.OPEN);
            assertThat(reopened.getReopenedAt()).isNotNull();
            assertThat(reopened.getResolvedAt()).isNotNull();   // 감사 이력은 지우지 않는다
            assertThat(reopenedCount()).isEqualTo(1.0);
            assertThat(SlackRecorderConfig.MESSAGES)
                    .hasSize(1)
                    .allSatisfy(m -> assertThat(m).contains("재개방").contains("직전상태=RESOLVED"));
        }

        @Test
        @DisplayName("V-15: DISCARDED root 도 같다 — discarded_at 유지")
        void reopensDiscardedRoot() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);
            endpoint.transition(root.getId(), "discard", "ops", "중복 이벤트로 판단해 재처리하지 않음");

            recorder.record(replayOrigin().build());

            DeadLetterRecord reopened = freshlyLoaded(root.getId());
            assertThat(reopened.statusValue()).isEqualTo(DeadLetterStatus.OPEN);
            assertThat(reopened.getReopenedAt()).isNotNull();
            assertThat(reopened.getDiscardedAt()).isNotNull();
        }

        @Test
        @DisplayName("V-15c: 로케이터 직후 다른 트랜잭션이 root 를 닫아도 재개방이 관측된다 — current read")
        void reopensRootClosedAfterLocatorRead() {
            // 단계 5 의 재조회를 일반 findById 로 바꾸면 red 다. REPEATABLE READ 에서 단계 1 이 연
            // 스냅샷을 다시 읽어 **과거의 OPEN 을 보고 재개방을 건너뛰기** 때문이다.
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);

            // **로케이터 반환 직후**에 다른 트랜잭션이 종결을 커밋한다. recorder 진입 전에 닫으면
            // 단계 1 스냅샷과 단계 2 잠금이 **둘 다 terminal 을 보게 되어**, 단계 5 를 일반 read 로
            // 바꿔도 통과한다 — 그러면 이 테스트가 아무것도 시험하지 않는다.
            interfereAfterLocator(root.getId(), () ->
                    endpoint.transition(root.getId(), "resolve", "ops", "조회로 해소 확인"));

            recorder.record(replayOrigin().build());

            DeadLetterRecord reopened = freshlyLoaded(root.getId());
            assertThat(reopened.statusValue()).isEqualTo(DeadLetterStatus.OPEN);
            assertThat(reopened.getReopenedAt()).isNotNull();
        }

        @Test
        @DisplayName("이미 OPEN 인 root 는 재개방으로 세지 않는다 — Counter 0, 알림 0")
        void openRootIsNotReopened() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);
            SlackRecorderConfig.MESSAGES.clear();

            recorder.record(replayOrigin().build());

            assertThat(childRow(root).getRootRecordId()).isEqualTo(root.getId());
            assertThat(meterRegistry.find("dlq.reopened").counter()).isNull();
            assertThat(SlackRecorderConfig.MESSAGES).isEmpty();
        }

        @Test
        @DisplayName("같은 DLT 재전달은 attempt_count 만 올린다 — 닫힌 root 를 다시 열지 않는다")
        void duplicateDeliveryDoesNotReopen() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);
            recorder.record(replayOrigin().build());
            endpoint.transition(root.getId(), "resolve", "ops", "조회로 해소 확인");
            SlackRecorderConfig.MESSAGES.clear();
            meterRegistry.clear();

            // 같은 좌표가 broker 재전달로 다시 온다.
            boolean inserted = recorder.record(replayOrigin().build());

            assertThat(inserted).isFalse();
            assertThat(freshlyLoaded(root.getId()).statusValue()).isEqualTo(DeadLetterStatus.RESOLVED);
            // 중복 유입에서는 대조가 계산되더라도 계측·알림·재개방을 전부 생략한다.
            assertThat(meterRegistry.find("dlq.correlation").counter()).isNull();
            assertThat(meterRegistry.find("dlq.reopened").counter()).isNull();
            assertThat(SlackRecorderConfig.MESSAGES).isEmpty();
        }
    }

    // ================= 알림 계약 =================

    @Nested
    @DisplayName("알림은 결과별로 정확히 한 종류다")
    class Notifications {

        @Test
        @DisplayName("독립 root 는 신규 미결 알림 1회, 재개방 알림 0회")
        void independentSendsNewIncidentOnly() {
            recorder.record(plainOrigin());

            assertThat(SlackRecorderConfig.MESSAGES)
                    .hasSize(1)
                    .allSatisfy(m -> assertThat(m).contains("신규 미결 1건"));
        }

        @Test
        @DisplayName("상관된 자식은 신규 미결 알림 0회 — backlog 가 늘지 않았으므로 그 문구는 거짓이다")
        void correlatedChildSendsNoNewIncident() {
            seedRootWithAnchor(PAYLOAD);
            SlackRecorderConfig.MESSAGES.clear();

            recorder.record(replayOrigin().build());

            assertThat(SlackRecorderConfig.MESSAGES).noneSatisfy(
                    m -> assertThat(m).contains("신규 미결 1건"));
        }

        @Test
        @DisplayName("V-15b: 알림 callback 이 실패해도 원장 재개방은 유지된다 — 소비 트랜잭션 재처리 없음")
        void notificationFailureDoesNotRollbackLedger() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);
            endpoint.transition(root.getId(), "resolve", "ops", "조회로 해소 확인");
            FailingSlack.armed = true;
            try {
                recorder.record(replayOrigin().build());   // 예외가 전파되지 않아야 한다
            } finally {
                FailingSlack.armed = false;
            }

            assertThat(freshlyLoaded(root.getId()).statusValue()).isEqualTo(DeadLetterStatus.OPEN);
        }
    }

    // ================= purge 경합 (V-21c) =================

    @Nested
    @DisplayName("purge 경합 — ④-c-2b-1 이 이연한 V-21c")
    class PurgeRace {

        @Test
        @DisplayName("V-21c: purge 가 후보를 조회한 뒤 재개방·자식 삽입이 커밋되면 root·자식이 살아남는다")
        void purgeDoesNotDeleteReopenedIncident() {
            // **재개방을 먼저 하고 purge 를 부르면 vacuous 하다** — root 가 애초에 후보에서 빠져
            // 경합을 시험하지 않는다. purge 가 stale id 를 **실제로 쥔** 상태를 만들어야 한다.
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);
            endpoint.transition(root.getId(), "resolve", "ops", "조회로 해소 확인");
            jdbcTemplate.update("UPDATE dead_letter_records SET resolved_at = ? WHERE id = ?",
                    java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(400)), root.getId());
            jdbcTemplate.update("UPDATE shedlock SET lock_until = NOW() - INTERVAL 1 DAY WHERE name = ?",
                    "deadLetterPurgeJob");

            AtomicBoolean fired = new AtomicBoolean(false);
            List<Long> staleCandidates = new CopyOnWriteArrayList<>();
            // 리포지토리는 인터페이스 프록시라 callRealMethod() 가 성립하지 않는다. 로케이터 seam 과 같이
            // **알려진 후보를 반환**한다 — 이 seam 이 고정하는 사실은 "purge 가 이 id 를 후보로 쥐었다" 이고,
            // 그 id 가 실제로 purge 대상 조건을 만족한다는 것은 위 fixture(종결 + cutoff 400일 경과)가 만든다.
            doAnswer(invocation -> {
                if (fired.compareAndSet(false, true)) {
                    staleCandidates.add(root.getId());
                    // 후보 반환 **직후** 다른 트랜잭션이 재개방 + 자식 삽입을 커밋한다.
                    ExecutorService executor = Executors.newSingleThreadExecutor();
                    try {
                        executor.submit(() -> recorder.record(replayOrigin().build()))
                                .get(20, TimeUnit.SECONDS);
                    } finally {
                        executor.shutdownNow();
                    }
                    return List.of(root.getId());
                }
                return List.<Long>of();   // 두 번째 배치부터는 비워 루프를 끝낸다
            }).when(repository).findPurgeableRootIds(any(), any());

            scheduler.purge();

            // ① purge 가 stale id 를 실제로 쥐고 있었다 (경합이 성립했다는 증거)
            assertThat(staleCandidates).contains(root.getId());
            // ② 그럼에도 root 와 자식이 살아남았다 — 잠금 후 재검사가 막았다
            assertThat(repository.findById(root.getId())).isPresent();
            assertThat(repository.findById(root.getId()).orElseThrow().statusValue())
                    .isEqualTo(DeadLetterStatus.OPEN);
            assertThat(repository.findAll()).hasSize(2);
        }
    }

    // ================= 계측 트랜잭션 의미 =================

    @Nested
    @DisplayName("Counter 는 commit 된 것만 센다")
    class CounterTransactionSemantics {

        @Test
        @DisplayName("rollback 되면 상관 Counter 가 올라가지 않는다 — 트랜잭션 안에서 세면 red")
        void rollbackLeavesNoCounterDelta() {
            // ④-d-1 3R #1 이 정확히 이 결함이었다. CommitAwareMetrics 를 쓰지 않고 트랜잭션 안에서
            // 올리면 rollback 된 상관까지 세면서 green 이 된다.
            seedRootWithAnchor(PAYLOAD);
            meterRegistry.clear();

            try {
                transactionTemplate.execute(status -> {
                    recorder.record(replayOrigin().build());
                    status.setRollbackOnly();
                    return null;
                });
            } catch (Exception ignored) {
                // rollback-only 커밋 시도가 예외로 끝날 수 있다 — 관심사는 Counter 다.
            }

            // **meter 자체는 존재할 수 있다** — Counter.builder(...).register() 는 증가 이전에 불린다.
            // 계약은 "등록되지 않는다" 가 아니라 **"commit 되지 않은 증가는 세지 않는다"** 이므로 delta 를 본다.
            assertThat(counterCount("dlq.correlation")).isZero();
            assertThat(counterCount("dlq.reopened")).isZero();
        }

        private double counterCount(String name) {
            return meterRegistry.find(name).counters().stream()
                    .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
        }
    }

    // ================= 사건 단위 집계 (V-16) =================

    @Nested
    @DisplayName("재실패가 쌓여도 미결은 1건 (V-16)")
    class IncidentAggregation {

        @Test
        @DisplayName("자식 3건이 상관돼도 행은 4개·backlog 는 1, root 종결 시 0")
        void threeChildrenKeepBacklogAtOne() {
            DeadLetterRecord root = seedRootWithAnchor(PAYLOAD);

            recorder.record(replayOrigin().offset(701L).build());
            recorder.record(replayOrigin().offset(702L).build());
            recorder.record(replayOrigin().offset(703L).build());

            assertThat(repository.findAll()).hasSize(4);
            assertThat(repository.findAll())
                    .filteredOn(r -> !r.getId().equals(root.getId()))
                    .allSatisfy(child -> assertThat(child.getRootRecordId()).isEqualTo(root.getId()));
            assertThat(repository.countUnresolved()).isEqualTo(1);

            endpoint.transition(root.getId(), "resolve", "ops", "조회로 해소 확인");
            assertThat(repository.countUnresolved()).isZero();
        }
    }

    // ================= 태그 경계 =================

    @Test
    @DisplayName("reason 태그는 bounded 집합 밖으로 나가지 않는다 — 입력 문자열은 태그가 되지 않는다")
    void reasonTagsAreBounded() {
        List<String> allowed = List.of("none", "no_attempt_header", "attempt_not_found", "owner_mismatch",
                "group_mismatch", "root_id_mismatch", "topic_mismatch", "fingerprint_mismatch",
                "digest_mismatch", "attempt_changed");

        seedRootWithAnchor(PAYLOAD);
        recorder.record(replayOrigin().build());
        recorder.record(replayOrigin().owner("payment").offset(999L).build());
        recorder.record(plainOrigin());

        assertThat(meterRegistry.find("dlq.correlation").counters())
                .isNotEmpty()
                .allSatisfy(c -> {
                    assertThat(c.getId().getTag("reason")).isIn(allowed);
                    assertThat(c.getId().getTag("result")).isIn("correlated", "independent");
                });
    }

    // ================= helpers =================

    /**
     * 로케이터({@code findRootIdByReplayAttemptId})가 <b>{@code rootId} 를 반환한 직후</b>
     * 다른 트랜잭션에서 {@code action} 을 커밋시킨다 (V-19m · V-15c).
     *
     * <p><b>fixture 를 미리 바꿔서는 이 경합이 재현되지 않는다.</b> recorder 진입 전에 상태를 바꾸면
     * 단계 1(로케이터)과 단계 2(잠금)가 <b>둘 다 바뀐 값을 보게 되어</b> 단계 3·5 의 변이가 관측되지 않는다.
     * 만들어야 하는 것은 "로케이터는 옛 세계를 보고 반환했는데 그 뒤 세계가 바뀐" 창이다.
     *
     * <p><b>반환값을 실제 쿼리로 다시 구하지 않는다.</b> Spring Data 리포지토리는 인터페이스 프록시라
     * {@code callRealMethod()} 가 성립하지 않고, 같은 JPQL 을 테스트에 복제하면 <b>SUT 를 테스트가
     * 다시 구현</b>하는 자기대조가 된다. 이 seam 이 고정하는 사실은 "로케이터가 이 id 를 돌려줬다" 하나이고,
     * 로케이터 자신의 정확성은 {@code V-19b}(탐색 실패)가 따로 지킨다.
     *
     * <p><b>1회만 개입하고 스스로 풀린다</b> — stub 이 남으면 뒤 테스트가 엉뚱한 개입을 받는다.
     *
     * <p><b>별도 스레드에서 커밋한다.</b> 같은 스레드면 recorder 의 트랜잭션 안이라 커밋이 따로 일어나지
     * 않고 "다른 트랜잭션이 끼어들었다" 는 전제가 성립하지 않는다.
     */
    private void interfereAfterLocator(Long rootId, Runnable action) {
        AtomicBoolean fired = new AtomicBoolean(false);
        doAnswer(invocation -> {
            if (fired.compareAndSet(false, true)) {
                // **평문 읽기를 한 번 한다.** 진짜 로케이터는 non-locking SELECT 라 이 시점에
                // InnoDB consistent-read 스냅샷이 열린다. stub 이 그 읽기를 통째로 없애면
                // 단계 5 의 평문 read 가 **이 트랜잭션의 첫 consistent read** 가 되어 최신 상태를 보고,
                // current read 로 바꾸든 말든 결과가 같아진다 — 그러면 V-15c 가 아무것도 시험하지 않는다.
                // root 엔티티를 직접 읽지는 않는다: 1차 캐시에 얹히면 단계 2 의 FOR UPDATE 가
                // 캐시 인스턴스를 돌려받아 refresh 하지 않는다(리포지토리 javadoc 이 경고하는 함정).
                repository.count();
                ExecutorService executor = Executors.newSingleThreadExecutor();
                try {
                    executor.submit(() -> transactionTemplate.execute(status -> {
                        action.run();
                        return null;
                    })).get(15, TimeUnit.SECONDS);
                } finally {
                    executor.shutdownNow();
                }
            }
            return Optional.of(rootId);
        }).when(repository).findRootIdByReplayAttemptId(anyString());
    }

    private void assertIndependent(String expectedReason) {
        List<DeadLetterRecord> rows = repository.findAll();
        DeadLetterRecord newest = rows.stream()
                .max((a, b) -> Long.compare(a.getId(), b.getId()))
                .orElseThrow();

        // **"행이 생겼다" 로는 부족하다** — 집계가 root_record_id IS NULL 도 root 로 세므로
        // assignSelfRoot() 를 빠뜨려도 backlog 단언은 green 이다.
        assertThat(newest.getRootRecordId()).isNotNull();
        assertThat(newest.getRootRecordId()).isEqualTo(newest.getId());
        assertThat(correlationCount("independent", expectedReason)).isEqualTo(1.0);
    }

    private double correlationCount(String result, String reason) {
        try {
            return meterRegistry.get("dlq.correlation").tag("result", result).tag("reason", reason)
                    .counter().count();
        } catch (MeterNotFoundException e) {
            return 0.0;
        }
    }

    private double reopenedCount() {
        try {
            return meterRegistry.get("dlq.reopened").counter().count();
        } catch (MeterNotFoundException e) {
            return 0.0;
        }
    }

    private DeadLetterRecord childRow(DeadLetterRecord root) {
        return repository.findAll().stream()
                .filter(r -> !r.getId().equals(root.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("자식 행이 적재되지 않았다"));
    }

    /** commit 후 <b>별도 트랜잭션</b>에서 읽는다 — 영속되지 않은 재개방을 green 으로 넘기지 않기 위해서다. */
    private DeadLetterRecord freshlyLoaded(Long id) {
        return transactionTemplate.execute(status -> repository.findById(id).orElseThrow());
    }

    private DeadLetterRecord seedRootWithAnchor(String payload) {
        return seedRootWithAnchor(payload, r -> {});
    }

    /** root 를 적재하고 replay 앵커를 심는다. 앵커 writer 는 2b-4 P21 이므로 여기서는 fixture 다. */
    private DeadLetterRecord seedRootWithAnchor(String payload, java.util.function.Consumer<RootSpec> customizer) {
        RootSpec spec = new RootSpec(payload);
        customizer.accept(spec);

        recorder.record(new DlqOrigin(
                DlqOriginKind.RESOLVED_ORIGIN, TOPIC, 0, 500L, GROUP,
                spec.key, spec.timestamp, "java.lang.IllegalStateException", "원본 실패",
                spec.payload, null, null, null, null));
        SlackRecorderConfig.MESSAGES.clear();

        DeadLetterRecord root = repository.findAll().get(0);
        jdbcTemplate.update("""
                        UPDATE dead_letter_records
                           SET last_replay_attempt_id = ?, last_replay_target_group = ?,
                               last_replay_payload_digest = ?, event_id = ?
                         WHERE id = ?
                        """,
                ATTEMPT, spec.anchorGroup, spec.digest, spec.eventId, root.getId());
        return transactionTemplate.execute(status -> repository.findById(root.getId()).orElseThrow());
    }

    /** root fixture 의 변이 지점. <b>한 테스트는 정확히 하나만</b> 바꾼다. */
    private static final class RootSpec {
        private String payload;
        private String key = KEY;
        private Long timestamp = TS;
        private String anchorGroup = GROUP;
        private String digest;
        private String eventId = "evt-1";

        RootSpec(String payload) {
            this.payload = payload;
            this.digest = PayloadDigest.sha256Hex(payload);
        }

        RootSpec key(String v) { this.key = v; return this; }
        RootSpec timestamp(Long v) { this.timestamp = v; return this; }
        RootSpec anchorGroup(String v) { this.anchorGroup = v; return this; }
        RootSpec digest(String v) { this.digest = v; return this; }
        RootSpec eventId(String v) { this.eventId = v; return this; }
    }

    private OriginBuilder replayOrigin() {
        return new OriginBuilder();
    }

    private DlqOrigin plainOrigin() {
        return new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, TOPIC, 0, 900L, GROUP,
                KEY, TS, "java.lang.IllegalStateException", "최초 실패", PAYLOAD,
                null, null, null, null);
    }

    /** 자식(재발행 재실패) 입력의 변이 지점. <b>한 테스트는 정확히 하나만</b> 바꾼다. */
    private final class OriginBuilder {
        private String topic = TOPIC;
        private long offset = 700L;
        private String actualGroup = GROUP;
        private String key = KEY;
        private Long timestamp = TS;
        private String payload = PAYLOAD;
        private String attempt = ATTEMPT;
        private String owner = OWNER;
        private String targetGroup = GROUP;
        private Long rootId;

        OriginBuilder topic(String v) { this.topic = v; return this; }
        OriginBuilder offset(long v) { this.offset = v; return this; }
        OriginBuilder actualGroup(String v) { this.actualGroup = v; return this; }
        OriginBuilder key(String v) { this.key = v; return this; }
        OriginBuilder payload(String v) { this.payload = v; return this; }
        OriginBuilder attempt(String v) { this.attempt = v; return this; }
        OriginBuilder owner(String v) { this.owner = v; return this; }
        OriginBuilder rootId(Long v) { this.rootId = v; return this; }

        DlqOrigin build() {
            Long resolvedRootId = rootId != null ? rootId : repository.findAll().stream()
                    .filter(DeadLetterRecord::isRoot).map(DeadLetterRecord::getId)
                    .findFirst().orElse(1L);
            return new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, topic, 0, offset, actualGroup,
                    key, timestamp, "java.lang.IllegalStateException", "재발행 후 재실패", payload,
                    attempt, owner, targetGroup, resolvedRootId);
        }
    }
}
