package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DLQ 원장의 <b>incident 집계</b> 회귀 (구현 ④-c-2b-1 P7 · 계획 §6 V-12·V-16·V-17·V-18 · ADR-0020 §D6-3).
 *
 * <p><b>여기서 지키는 것은 "행이 아니라 사건을 센다" 하나다.</b> 재발행이 실패할 때마다 자식 행이
 * 늘어나는데 행으로 세면 backlog 가 사건 수보다 계속 부풀고, ADR 이 약속한 "재실패 N회에도 미결 1건"이
 * 거짓이 된다. 반대로 조건을 {@code root_record_id = id} 로만 걸면 <b>④-c-2a 가 적재한 기존 행이 전부
 * 탈락해 backlog 가 0 으로 보인다</b> — 같은 표면에서 반대 방향의 false-green 이 난다. 두 방향을 함께 고정한다.
 *
 * <p><b>자식 행을 fixture 로 만드는 이유</b>: 자식을 만드는 실제 경로(재발행 재실패 상관)는 ④-c-2b-3 P15
 * 소관이라 아직 없다. 이 테스트는 그 경로가 만들 <b>DB 상태</b>를 직접 구성해 집계·종결·purge 계약을
 * 먼저 고정한다. 상태 구성에만 SQL/엔티티를 쓰고, <b>전이는 전부 공개 진입점</b>({@link DeadLetterEndpoint})
 * 으로만 한다 — ④-c-2a 에서 runbook 이 직접 UPDATE 를 안내해 가드를 우회시킨 전례가 있다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("DLQ 원장 incident 집계 회귀")
class DeadLetterIncidentAggregationIntegrationTest extends AbstractIntegrationTest {

    @Autowired DeadLetterRecorder recorder;
    @Autowired DeadLetterRecordJpaRepository repository;
    @Autowired DeadLetterEndpoint endpoint;
    @Autowired DeadLetterMaintenanceScheduler scheduler;
    @Autowired MeterRegistry meterRegistry;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    private static final String GROUP = "order-svc-payment-completed-group";

    @BeforeEach
    void setUp() {
        cleanDatabase();
        repository.deleteAll();
        // purge 잡의 lockAtLeastFor 는 PT1M 이라, 해제하지 않으면 **두 번째 이후 purge 호출이 통째로
        // 건너뛰어지고** 그 테스트들은 "아무 것도 안 지워졌다" 를 관측하며 green 이 된다 — false-green 이다.
        //
        // **행을 DELETE 하면 오히려 영구히 잠긴다**: ShedLock 의 StorageBasedLockProvider 는 이미 만들어 본
        // 락 이름을 JVM 내 registry 에 기억해 두고 이후에는 INSERT 가 아니라 UPDATE 만 시도한다. 행이 없으면
        // 그 UPDATE 가 0행이라 "It's locked" 로 판정된다. 그래서 지우는 대신 **만료시킨다**.
        // (`usingDbTime()` 이므로 DB 시각 기준이다.)
        jdbcTemplate.update("UPDATE shedlock SET lock_until = NOW() - INTERVAL 1 DAY WHERE name = ?",
                "deadLetterPurgeJob");
    }

    // ---------- 신규 적재는 self-root 다 (P3) ----------

    @Test
    @DisplayName("신규 적재 행은 root_record_id = id 로 자기 자신을 가리킨다")
    void newRecordIsSelfRoot() {
        recorder.record(origin(1, 100L));

        DeadLetterRecord record = repository.findAll().get(0);
        assertThat(record.getRootRecordId()).isEqualTo(record.getId());
        assertThat(record.isRoot()).isTrue();
    }

    // ---------- 집계는 incident 단위다 (V-16) ----------

    @Test
    @DisplayName("재실패 자식이 3건 붙어도 backlog 는 1 — 행이 아니라 사건을 센다")
    void childrenDoNotInflateBacklog() {
        DeadLetterRecord root = recordRoot(1, 100L);
        childOf(root, 2, 201L);
        childOf(root, 2, 202L);
        childOf(root, 2, 203L);

        assertThat(repository.findAll()).hasSize(4);
        assertThat(repository.countUnresolved()).isEqualTo(1);
        assertThat(gauge("dlq.backlog")).isEqualTo(1.0);
        assertThat(endpoint.backlog().get("unresolved")).isEqualTo(1L);
    }

    @Test
    @DisplayName("root 를 종결하면 backlog 0 — 자식도 함께 닫힌다")
    void resolvingRootClosesIncident() {
        DeadLetterRecord root = recordRoot(1, 100L);
        DeadLetterRecord child = childOf(root, 2, 201L);

        endpoint.transition(root.getId(), "resolve", "ops", "주문 상태가 CONFIRMED 로 도달했음을 조회로 확인");

        assertThat(repository.countUnresolved()).isZero();
        assertThat(repository.findById(root.getId()).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.RESOLVED);
        assertThat(repository.findById(child.getId()).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.RESOLVED);
    }

    // ---------- 발행 성공은 종결이 아니다 (V-12) ----------

    @Test
    @DisplayName("publication_status=PUBLISHED 인 root 도 backlog·oldest-age 에 계속 잡힌다")
    void publishedRootStaysUnresolved() {
        DeadLetterRecord root = recordRoot(1, 100L);
        setPublicationStatus(root.getId(), PublicationStatus.PUBLISHED);

        assertThat(repository.countUnresolved()).isEqualTo(1);
        assertThat(gauge("dlq.backlog")).isEqualTo(1.0);
        assertThat(repository.findOldestUnresolvedOccurredAt()).isPresent();

        Map<String, Object> backlog = endpoint.backlog();
        assertThat(backlog.get("unresolved")).isEqualTo(1L);
        @SuppressWarnings("unchecked")
        Map<String, Object> publication = (Map<String, Object>) backlog.get("publication");
        assertThat(publication.get("PUBLISHED")).isEqualTo(1L);
        assertThat(publication.get("REQUESTED")).isEqualTo(0L);
        assertThat(publication.get("NOT_REQUESTED")).isEqualTo(0L);
    }

    @Test
    @DisplayName("replay 를 요청한 적 없는 미결도 발행 분포에 잡힌다 — 네 값의 합 = unresolved")
    void publicationSummaryCoversNotRequested() {
        recordRoot(1, 100L);
        recordRoot(1, 101L);

        Map<String, Object> backlog = endpoint.backlog();
        @SuppressWarnings("unchecked")
        Map<String, Object> publication = (Map<String, Object>) backlog.get("publication");

        // NULL(요청 없음)을 빼면 미결 2건인데 분포가 전부 0 이 되어 "아무 것도 없다" 로 읽힌다.
        assertThat(publication.get("NOT_REQUESTED")).isEqualTo(2L);
        long sum = publication.values().stream().mapToLong(v -> (Long) v).sum();
        assertThat(sum).isEqualTo(backlog.get("unresolved"));
    }

    // ---------- 전환 구간: 기존 행은 탈락하지 않는다 (V-18) ----------

    @Test
    @DisplayName("root_record_id 가 NULL 인 기존 행도 집계에 잡힌다 — 컬럼 도입이 backlog 를 0 으로 만들지 않는다")
    void legacyRowsWithoutRootAreStillCounted() {
        recordRoot(1, 100L);
        recordRoot(1, 101L);
        recordRoot(1, 102L);
        // ④-c-2a 가 적재한 상태 재현: 컬럼이 없던 시절의 행은 root_record_id 가 NULL 이다.
        jdbcTemplate.update("UPDATE dead_letter_records SET root_record_id = NULL");

        assertThat(repository.countUnresolved()).isEqualTo(3);
        assertThat(repository.findOldestUnresolvedOccurredAt()).isPresent();
        assertThat(repository.findStaleUnresolved(LocalDateTime.now().plusDays(1),
                org.springframework.data.domain.PageRequest.of(0, 10))).hasSize(3);
    }

    @Test
    @DisplayName("root_record_id 가 NULL 인 기존 행도 종결할 수 있다 — 전환 구간 행이 '원장 없음' 으로 거부되지 않는다")
    void legacyRowWithoutRootCanBeTransitioned() {
        DeadLetterRecord root = recordRoot(1, 100L);
        jdbcTemplate.update("UPDATE dead_letter_records SET root_record_id = NULL WHERE id = ?", root.getId());

        Map<String, Object> response = endpoint.transition(root.getId(), "acknowledge", "ops", null);

        // root id 해석이 rootRecordId 를 그대로 돌려주는 구현이면 NULL 이 되어 조회가 비고 "원장 없음" 이 된다.
        assertThat(response.get("rootId")).isEqualTo(root.getId());
        assertThat(response.get("changed")).isEqualTo(true);
        assertThat(repository.findById(root.getId()).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.ACKED);
    }

    // ---------- 종결은 root 로 정규화된다 (V-17) ----------

    @Test
    @DisplayName("자식 id 로 종결 요청해도 root 로 정규화되어 root + 자식이 함께 닫힌다")
    void childIdIsNormalizedToRoot() {
        DeadLetterRecord root = recordRoot(1, 100L);
        DeadLetterRecord child = childOf(root, 2, 201L);

        Map<String, Object> response =
                endpoint.transition(child.getId(), "discard", "ops", "상류에서 직접 교정 완료");

        assertThat(response.get("rootId")).isEqualTo(root.getId());
        assertThat(response.get("changed")).isEqualTo(true);
        assertThat(repository.findById(root.getId()).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(repository.findById(child.getId()).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.DISCARDED);
        assertThat(repository.countUnresolved()).isZero();
    }

    // ---------- RESOLVED 는 근거가 필수다 (P2) ----------

    @Test
    @DisplayName("resolve 는 근거 없이 불가 — DISCARDED 와 구분되지 않는 종결을 만들지 않는다")
    void resolveRequiresEvidence() {
        DeadLetterRecord root = recordRoot(1, 100L);

        assertThat(endpoint.transition(root.getId(), "resolve", "ops", "  "))
                .containsEntry("error", "RESOLVED 는 해소를 확인한 근거 기록이 필수입니다");
        assertThat(repository.findById(root.getId()).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.OPEN);

        assertThatThrownBy(() -> repository.findById(root.getId()).orElseThrow().resolve("ops", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- 잠금 재검사가 최신 상태를 본다 (diff 리뷰 1R #2) ----------

    @Test
    @DisplayName("종결 경합 — 잠금을 기다린 쪽은 **먼저 커밋된 종결을 보고** root·자식 모두 no-op 한다")
    void concurrentTransitionSeesCommittedTerminalState() throws Exception {
        DeadLetterRecord root = recordRoot(1, 100L);
        DeadLetterRecord child = childOf(root, 2, 201L);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch discardBlocked = new CountDownLatch(1);

        // (1) 트랜잭션 A: root 와 자식을 잠근 채 RESOLVED 로 전이하고, B 가 **실제로 잠금 대기에 들어간 뒤**
        //     커밋한다. 자식까지 전이해야 "root 는 no-op 하면서 자식만 덮어쓰는" 경로를 관측할 수 있다.
        CompletableFuture<Void> resolver = CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(tx -> {
            DeadLetterRecord held = repository.findByIdForUpdate(root.getId()).orElseThrow();
            held.resolve("ops-a", "도메인 상태 확인 완료");
            repository.findChildrenForUpdate(root.getId()).forEach(c -> c.resolve("ops-a", "root 와 함께"));
            repository.flush();
            locked.countDown();
            awaitLatch(discardBlocked);
        }));

        assertThat(locked.await(20, TimeUnit.SECONDS)).isTrue();

        // (2) 트랜잭션 B: 같은 incident 에 discard 를 건다 — A 가 커밋할 때까지 행 잠금에서 블록된다.
        //     B 를 명시적 트랜잭션으로 감싸 **그 커넥션 id 를 밖으로 알린다**. 그래야 아래에서
        //     "무언가가 잠금을 기다린다" 가 아니라 "**바로 이 B 가** 기다린다" 를 확인할 수 있다.
        java.util.concurrent.atomic.AtomicLong discarderConnectionId = new java.util.concurrent.atomic.AtomicLong();
        CountDownLatch connectionKnown = new CountDownLatch(1);
        CompletableFuture<Map<String, Object>> discarder = CompletableFuture.supplyAsync(() ->
                transactionTemplate.execute(tx -> {
                    // CONNECTION_ID() 는 InnoDB 테이블을 읽지 않으므로 consistent-read 스냅샷을 열지 않는다
                    // — 스냅샷은 아래 transition 의 첫 조회에서 열린다(회귀 관측 대상이 유지된다).
                    discarderConnectionId.set(jdbcTemplate.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    connectionKnown.countDown();
                    return endpoint.transition(root.getId(), "discard", "ops-b", "정리");
                }));

        // **sleep 으로 추정하지 않는다.** B 가 실제로 InnoDB lock wait 에 들어갔음을 DB 에 물어 확인한다 —
        // 고정 대기는 느린 CI 에서 B 가 아직 시작도 못 한 채 A 가 커밋해, 결함 변이를 확률적으로 놓친다.
        assertThat(connectionKnown.await(20, TimeUnit.SECONDS)).isTrue();
        awaitLockWait(discarderConnectionId.get());
        discardBlocked.countDown();

        resolver.get(30, TimeUnit.SECONDS);
        Map<String, Object> result = discarder.get(30, TimeUnit.SECONDS);

        // B 는 잠금 획득 후 **DB 의 최신 상태**를 읽어야 한다. root 든 자식이든 스냅샷의 과거 OPEN 을
        // 보면 앞선 종결을 조용히 뒤집는다.
        assertThat(result.get("changed")).isEqualTo(false);
        assertThat(repository.findById(root.getId()).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.RESOLVED);
        assertThat(repository.findById(child.getId()).orElseThrow().statusValue())
                .isEqualTo(DeadLetterStatus.RESOLVED);
    }

    // ---------- purge 는 incident 단위 + 현재 상태의 시각만 본다 (P4) ----------

    @Test
    @DisplayName("종결된 root 를 purge 하면 자식도 함께 사라진다 — 자식 단독 purge 경로는 없다")
    void purgeRemovesWholeIncident() {
        DeadLetterRecord root = recordRoot(1, 100L);
        childOf(root, 2, 201L);
        endpoint.transition(root.getId(), "resolve", "ops", "도메인 상태 확인 완료");
        backdate(root.getId(), "resolved_at", LocalDateTime.now().minusDays(100));

        scheduler.purge();

        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("미결 root 는 purge 대상이 아니다 — 장기 미결은 용량 문제가 아니라 SLA 문제다")
    void purgeKeepsUnresolved() {
        recordRoot(1, 100L);

        scheduler.purge();

        assertThat(repository.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("DISCARDED 이력이 남은 RESOLVED root 는 resolved_at 기준으로만 판정된다 (COALESCE 회귀)")
    void purgeUsesCurrentStatusTimestamp() {
        DeadLetterRecord root = recordRoot(1, 100L);
        // 재개방(④-c-2b-3 P15)이 만드는 상태 재현: 과거 DISCARDED 이력이 남은 채 현재는 RESOLVED 다.
        // COALESCE(discarded_at, resolved_at) 였다면 **과거 discarded_at** 이 골라져 보존기간이 지나기 전에
        // 삭제된다. 현재 상태에 해당하는 시각(resolved_at)만 봐야 한다.
        jdbcTemplate.update("UPDATE dead_letter_records SET status = 'RESOLVED', "
                        + "discarded_at = ?, resolved_at = ? WHERE id = ?",
                LocalDateTime.now().minusDays(100), LocalDateTime.now(), root.getId());

        // **조회 계약을 직접 단언한다.** scheduler.purge() 결과만 보면 이 테스트는 vacuous 하다 —
        // 잠금 후 인메모리 재검사(isPurgeable)가 어차피 걸러내므로 쿼리를 COALESCE 로 되돌려도 green 이 된다.
        // (변이 검증에서 실제로 그랬다.) 두 방어선을 각각 단언한다.
        assertThat(repository.findPurgeableRootIds(LocalDateTime.now().minusDays(90),
                org.springframework.data.domain.PageRequest.of(0, 10))).isEmpty();

        scheduler.purge();

        assertThat(repository.findAll()).hasSize(1);
    }

    // ---------- helpers ----------

    private DeadLetterRecord recordRoot(int partition, long offset) {
        recorder.record(origin(partition, offset));
        return repository.findByClusterIdAndTopicGenerationAndOriginTopicAndOriginPartitionAndOriginOffsetAndFailedConsumerGroup(
                        "peekcart-local", 1, "payment.completed", partition, offset, GROUP)
                .orElseThrow();
    }

    /**
     * 재발행 재실패로 생기는 자식 행을 만든다. 실제 생성 경로는 ④-c-2b-3 P15 소관이라
     * 여기서는 그 경로가 만들 DB 상태를 직접 구성한다.
     */
    private DeadLetterRecord childOf(DeadLetterRecord root, int partition, long offset) {
        DeadLetterRecord child = DeadLetterRecord.open(
                "peekcart-local", 1, rawOrigin(partition, offset), "evt-child-" + offset, "{}", false);
        child.linkToRoot(root.getId());
        return repository.save(child);
    }

    /**
     * 보존기간(기본 90d)을 넘긴 종결 건을 만든다. 설정을 줄이는 대신 <b>시각을 과거로 옮기는</b> 이유는
     * {@code retention=0s} 로는 "지금 막 종결한 건" 과 "보존기간을 넘긴 건" 이 구분되지 않아
     * 경계 테스트가 의미를 잃기 때문이다.
     */
    private void backdate(Long id, String column, LocalDateTime when) {
        jdbcTemplate.update("UPDATE dead_letter_records SET " + column + " = ? WHERE id = ?", when, id);
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("latch 대기 timeout — 경합 순서가 성립하지 않았다");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 다른 커넥션이 InnoDB 행 잠금을 <b>실제로</b> 기다리고 있음을 DB 에 물어 확인한다.
     *
     * <p><b>커넥션 id 로 대상을 특정한다.</b> 전체 건수만 보면 스케줄러 등 <b>무관한 트랜잭션</b>의 잠금
     * 대기 하나에도 latch 가 풀려, discarder 가 아직 진입도 못 한 채 resolver 가 커밋한다 — 그러면
     * 이 테스트가 잡으려는 stale-snapshot 변이를 다시 확률적으로 놓친다.
     *
     * <p>{@code information_schema.INNODB_TRX} 는 {@code PROCESS} 권한이 필요해 컨테이너의 앱 계정으로는
     * 읽을 수 없다. 그래서 root 커넥션을 따로 연다 — 이 관측은 테스트 하네스의 동기화 수단이지
     * 애플리케이션 경로가 아니다.
     */
    private void awaitLockWait(long connectionId) {
        org.awaitility.Awaitility.await().atMost(20, TimeUnit.SECONDS)
                .pollInterval(java.time.Duration.ofMillis(50))
                .until(() -> {
                    try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
                            SharedContainers.MYSQL.getJdbcUrl(), "root", SharedContainers.MYSQL.getPassword());
                         java.sql.PreparedStatement st = conn.prepareStatement(
                                 "SELECT COUNT(*) FROM information_schema.INNODB_TRX "
                                         + "WHERE trx_state = 'LOCK WAIT' AND trx_mysql_thread_id = ?")) {
                        st.setLong(1, connectionId);
                        try (java.sql.ResultSet rs = st.executeQuery()) {
                            return rs.next() && rs.getInt(1) > 0;
                        }
                    }
                });
    }

    private void setPublicationStatus(Long id, PublicationStatus status) {
        jdbcTemplate.update("UPDATE dead_letter_records SET publication_status = ? WHERE id = ?",
                status.name(), id);
    }

    private DlqOrigin origin(int partition, long offset) {
        return rawOrigin(partition, offset);
    }

    private DlqOrigin rawOrigin(int partition, long offset) {
        return new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "payment.completed", partition, offset,
                GROUP, "order-1", 1_700_000_000_000L, "java.lang.IllegalStateException", "boom", "{}",
                null, null, null, null);
    }

    private double gauge(String name) {
        return meterRegistry.get(name).gauge().value();
    }
}
