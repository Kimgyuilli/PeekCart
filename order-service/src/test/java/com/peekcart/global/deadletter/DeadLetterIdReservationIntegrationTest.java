package com.peekcart.global.deadletter;

import com.peekcart.global.kafka.DlqOrigin;
import com.peekcart.global.kafka.DlqOriginKind;
import com.peekcart.global.kafka.PayloadDigest;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.reset;

@SpringBootTest
@TestPropertySource(properties = {"spring.flyway.enabled=true", "spring.flyway.locations=classpath:db/migration"})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("DLQ 예약 ID와 실제 JPA 적재 트랜잭션")
class DeadLetterIdReservationIntegrationTest extends AbstractIntegrationTest {
    @Autowired DeadLetterRecorder recorder;
    @Autowired DeadLetterRecordJpaRepository repository;
    @MockitoSpyBean DeadLetterIdAllocator allocator;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired TransactionTemplate tx;

    @BeforeEach
    void clean() {
        reset(allocator);
        cleanDatabase();
    }

    @Test
    void allocatorRequiresExistingTransaction() {
        assertThatThrownBy(() -> allocator.allocate()).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void firstInsertHasSelfRootEvenUnderNotNullContract() {
        // NOT NULL 은 contract migration 이 이미 걸었다. 공유 DB 계약을 되돌리지 않도록 양수 CHECK 만 잠시 더한다.
        assertThat(jdbc.queryForObject("SELECT IS_NULLABLE FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dead_letter_records' "
                + "AND COLUMN_NAME = 'root_record_id'", String.class)).isEqualTo("NO");
        jdbc.execute("ALTER TABLE dead_letter_records ADD CONSTRAINT d065_positive_root CHECK(root_record_id>0)");
        try {
            assertThat(recorder.record(origin(10))).isTrue();
            DeadLetterRecord row = repository.findAll().get(0);
            assertThat(row.getRootRecordId()).isEqualTo(row.getId()).isPositive();
            String attempt = "11111111-1111-4111-8111-111111111111";
            String group = "order-svc-payment-completed-group";
            jdbc.update("UPDATE dead_letter_records SET last_replay_attempt_id=?, last_replay_target_group=?, "
                    + "last_replay_payload_digest=? WHERE id=?", attempt, group, PayloadDigest.sha256Hex("{}"), row.getId());
            DlqOrigin child = new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "payment.completed", 0, 11,
                    group, "key", 1700000000000L, "E", "message", "{}", attempt, "order", group, row.getId());
            assertThat(recorder.record(child)).isTrue();
            assertThat(repository.findAll()).filteredOn(r -> r.getOriginOffset() == 11).singleElement()
                    .satisfies(r -> assertThat(r.getRootRecordId()).isEqualTo(row.getId()).isNotEqualTo(r.getId()));
            assertNoReservations();
        } finally {
            jdbc.execute("ALTER TABLE dead_letter_records DROP CHECK d065_positive_root");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void concurrentDuplicateOrDistinctCoordinates(boolean distinct) throws Exception {
        int count = 8;
        var executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var results = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < count; i++) {
                long offset = distinct ? 100 + i : 100;
                results.add(executor.submit(() -> { start.await(); return recorder.record(origin(offset)); }));
            }
            start.countDown();
            int inserted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) inserted++;
            assertThat(inserted).isEqualTo(distinct ? count : 1);
            assertThat(repository.count()).isEqualTo(distinct ? count : 1);
            assertThat(repository.findAll()).allSatisfy(row -> {
                assertThat(row.getRootRecordId()).isEqualTo(row.getId());
                assertThat(row.getAttemptCount()).isEqualTo(distinct ? 1 : count);
            });
            assertNoReservations();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recorderJoinsOuterTransactionAndRollsBackLedgerAndReservation() {
        AtomicLong rolledBackId = new AtomicLong();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            recorder.record(origin(200));
            rolledBackId.set(repository.findAll().get(0).getId());
            throw new IllegalStateException("injected after actual repository insert");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(repository.count()).isZero();
        assertNoReservations();
        recorder.record(origin(201));
        assertThat(repository.findAll().get(0).getId()).isGreaterThan(rolledBackId.get());
    }

    @Test
    void constraintFailureDoesNotBecomeDuplicateAndCaughtExceptionPoisonsOuterTransaction() {
        DlqOrigin invalid = new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "payment.completed", 0, 300,
                "x".repeat(1000), "key", 1700000000000L, "E", "message", "{}", null, null, null, null);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            try {
                recorder.record(invalid);
                throw new AssertionError("SQL truncation must not be ignored");
            } catch (DataIntegrityViolationException expected) {
                // Catching the repository failure does not permit a successful outer commit.
            }
        })).isInstanceOf(UnexpectedRollbackException.class);
        assertThat(repository.count()).isZero();
        assertNoReservations();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void occupiedAllocatedIdNeverAcknowledgesReuse(int collisionKind) {
        recorder.record(origin(400));
        long occupied = repository.findAll().get(0).getId();
        if (collisionKind == 1) recorder.record(origin(401));
        tx.executeWithoutResult(status -> doReturn(occupied).when(allocator).allocate());
        assertThatThrownBy(() -> recorder.record(origin(collisionKind == 2 ? 400 : 401)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("충돌");
        assertThat(repository.count()).isEqualTo(collisionKind == 1 ? 2 : 1);
        assertThat(repository.findAll()).allSatisfy(row -> assertThat(row.getAttemptCount()).isEqualTo(1));
        assertNoReservations();
    }

    @Test
    void duplicateAttemptOverflowRollsBackAndLaterRetryIncrementsOnce() {
        recorder.record(origin(450));
        jdbc.update("UPDATE dead_letter_records SET attempt_count=?", Integer.MAX_VALUE);
        assertThatThrownBy(() -> recorder.record(origin(450))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(repository.findAll()).singleElement()
                .satisfies(row -> assertThat(row.getAttemptCount()).isEqualTo(Integer.MAX_VALUE));
        assertNoReservations();
        jdbc.update("UPDATE dead_letter_records SET attempt_count=1");
        assertThat(recorder.record(origin(450))).isFalse();
        assertThat(repository.findAll()).singleElement().satisfies(row -> assertThat(row.getAttemptCount()).isEqualTo(2));
        assertNoReservations();
    }

    @Test
    void attemptCounterNullCannotEnterTheUpsertContract() {
        recorder.record(origin(451));
        assertThatThrownBy(() -> jdbc.update("UPDATE dead_letter_records SET attempt_count=NULL"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(recorder.record(origin(451))).isFalse();
        assertThat(repository.findAll()).singleElement().satisfies(row -> assertThat(row.getAttemptCount()).isEqualTo(2));
    }

    @Test
    void repeatableReadSnapshotFindsConcurrentCoordinateWinner() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                assertThat(repository.count()).isZero(); // establish RR snapshot
                try {
                    assertThat(executor.submit(() -> recorder.record(origin(500))).get(10, TimeUnit.SECONDS)).isTrue();
                } catch (Exception e) { throw new IllegalStateException(e); }
                assertThat(repository.count()).isZero(); // plain read is still the older snapshot
                assertThat(recorder.record(origin(500))).isFalse();
            });
            assertThat(repository.findAll()).singleElement().satisfies(row -> {
                assertThat(row.getAttemptCount()).isEqualTo(2);
                assertThat(row.getRootRecordId()).isEqualTo(row.getId());
            });
            assertNoReservations();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void pendingAllocationDoesNotBlockAnotherTransaction() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                long first = allocator.allocate();
                try {
                    long second = executor.submit(() -> tx.execute(s -> allocator.allocate())).get(5, TimeUnit.SECONDS);
                    assertThat(second).isGreaterThan(first);
                } catch (Exception e) { throw new IllegalStateException(e); }
                status.setRollbackOnly();
            });
            assertNoReservations();
        } finally {
            executor.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {0, 1000000000000L})
    void actualMigrationBootstrapsEmptyOrHighLegacyTableWithoutRewrite(long existing) throws Exception {
        String migration = new ClassPathResource("db/migration/V14__dead_letter_id_reservation.sql")
                .getContentAsString(StandardCharsets.UTF_8).replace("CREATE TABLE", "CREATE TEMPORARY TABLE");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute("CREATE TEMPORARY TABLE dead_letter_records (id BIGINT PRIMARY KEY, root_record_id BIGINT NULL)");
                if (existing > 0) statement.execute("INSERT INTO dead_letter_records VALUES(" + existing + ",NULL)");
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(migration.getBytes(StandardCharsets.UTF_8)));
                statement.executeUpdate("INSERT INTO dead_letter_record_ids () VALUES ()", Statement.RETURN_GENERATED_KEYS);
                try (var generated = statement.getGeneratedKeys()) {
                    assertThat(generated.next()).isTrue();
                    assertThat(generated.getLong(1)).isEqualTo(existing + 1);
                }
                if (existing > 0) try (var legacy = statement.executeQuery("SELECT id,root_record_id FROM dead_letter_records")) {
                    assertThat(legacy.next()).isTrue();
                    assertThat(legacy.getLong(1)).isEqualTo(existing);
                    assertThat(legacy.getObject(2)).isNull();
                }
            } finally {
                statement.execute("DROP TEMPORARY TABLE IF EXISTS dead_letter_record_ids");
                statement.execute("DROP TEMPORARY TABLE IF EXISTS dead_letter_records");
            }
        }
    }

    private void assertNoReservations() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_record_ids", Long.class)).isZero();
    }

    private DlqOrigin origin(long offset) {
        return new DlqOrigin(DlqOriginKind.RESOLVED_ORIGIN, "payment.completed", 0, offset,
                "order-svc-payment-completed-group", "key", 1700000000000L, "E", "message", "{}",
                null, null, null, null);
    }
}
