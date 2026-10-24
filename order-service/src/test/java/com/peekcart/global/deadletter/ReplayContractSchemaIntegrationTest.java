package com.peekcart.global.deadletter;

import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.SQLException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * replay 판별자 NOT NULL contract 회귀 (D-065, ADR-0020 D3·§D6-3).
 *
 * <p>이 서비스의 Flyway 체인이 만든 실제 스키마에서 {@code root_record_id}·{@code record_kind} 를 빠뜨린 INSERT 가
 * 실패하는지 본다. <b>같은 문장에 값만 넣으면 성공해야 한다</b> — 그래야 실패가 다른 제약이 아니라 누락 때문임이
 * 증명된다. 4서비스가 같은 본문을 갖는다. 마이그레이션은 서비스마다 따로 있고 한 모듈의 테스트는 다른 모듈의
 * 스키마를 보지 못한다.
 *
 * <p>전환 구간의 NULL 해석을 고정하던 V-18(NULL root 집계·종결)과 V-30(NULL kind 도메인 발행)을 대체한다.
 * contract 이후 NULL 은 저장될 수 없으므로 고정할 대상은 "누락이 실패한다" 이다.
 *
 * <p>order 에만 {@link #classpathGuardRejectsBeforeAnyMigration} 이 있다. Spring 의 Flyway 가 classpath 에서
 * beforeMigrate 가드를 실제로 찾는지 보는 음성 대조다. 가드 파일은 4서비스가 바이트 동일 사본이다
 * (dead-letter-schema-parity-lint DLQ-PARITY-027·028).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("order-service replay 판별자 NOT NULL contract")
class ReplayContractSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    @Test
    @DisplayName("두 판별자 컬럼은 NOT NULL 이고 DB DEFAULT 가 없다")
    void discriminatorsAreNotNullWithoutDefault() {
        assertThat(column("dead_letter_records", "root_record_id"))
                .containsEntry("IS_NULLABLE", "NO").containsEntry("COLUMN_DEFAULT", null);
        assertThat(column("outbox_events", "record_kind"))
                .containsEntry("IS_NULLABLE", "NO").containsEntry("COLUMN_DEFAULT", null);
    }

    @Test
    @DisplayName("root_record_id 를 빠뜨린 원장 INSERT 와 NULL 로 되돌리는 UPDATE 는 거부되고 값을 넣으면 들어간다 (V-18 대체)")
    void ledgerRootIsRequired() {
        assertThatThrownBy(() -> jdbc.update(ledgerInsert(false)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(mysqlCode(error)).isEqualTo(1364));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dead_letter_records", Long.class)).isZero();

        // 양성 대조 — 같은 문장에 root 만 넣었다. 값의 정합(root 행 존재)은 writer 가 지킨다.
        assertThat(jdbc.update(ledgerInsert(true))).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE dead_letter_records SET root_record_id = NULL WHERE cluster_id = 'contract-fixture'"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(mysqlCode(error)).isEqualTo(1048));
        assertThat(jdbc.queryForObject(
                "SELECT root_record_id FROM dead_letter_records WHERE cluster_id = 'contract-fixture'", Long.class))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("record_kind 를 빠뜨린 outbox INSERT 는 거부되고 값을 넣으면 들어간다 (V-30 대체)")
    void outboxKindIsRequired() {
        assertThatThrownBy(() -> jdbc.update(outboxInsert(false)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertThat(mysqlCode(error)).isEqualTo(1364));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Long.class)).isZero();

        assertThat(jdbc.update(outboxInsert(true))).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT record_kind FROM outbox_events WHERE event_id = 'contract-fixture-event'", String.class))
                .isEqualTo("DOMAIN");
    }

    @Test
    @DisplayName("classpath 의 beforeMigrate 가드가 nullable 상태의 NULL root 를 migration 전에 거부하고 history 를 남기지 않는다")
    void classpathGuardRejectsBeforeAnyMigration() {
        // 공유 DB 는 contract 이후라 가드가 꺼져 있다. 가드가 켜지는 상태(nullable + NULL 행)를 잠시 만들어
        // Spring 의 Flyway 가 classpath 에서 가드를 찾는지 본다. 못 찾으면 migrate 는 할 일 없이 성공한다.
        jdbc.execute("ALTER TABLE dead_letter_records MODIFY root_record_id BIGINT NULL");
        try {
            jdbc.update(ledgerInsert(false));
            long historyRows = historyRows();

            assertThatThrownBy(flyway::migrate)
                    .hasStackTraceContaining("beforeMigrate__dead_letter_replay_contract_guard.sql")
                    .hasStackTraceContaining("D065_CONTRACT_GUARD invalid_root_rows=1 invalid_kind_rows=0")
                    .satisfies(error -> assertThat(mysqlCode(error)).isEqualTo(1231));
            assertThat(historyRows()).isEqualTo(historyRows);
            assertThat(column("dead_letter_records", "root_record_id")).containsEntry("IS_NULLABLE", "YES");
        } finally {
            jdbc.update("DELETE FROM dead_letter_records");
            jdbc.execute("ALTER TABLE dead_letter_records MODIFY root_record_id BIGINT NOT NULL");
        }
        assertThat(column("dead_letter_records", "root_record_id")).containsEntry("IS_NULLABLE", "NO");
        // contract 이후 가드는 information_schema 만 읽고 지나간다 — 매 부팅 실행이 무해하다.
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    private Map<String, Object> column(String table, String column) {
        return jdbc.queryForMap("""
                SELECT IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, table, column);
    }

    private long historyRows() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history", Long.class);
    }

    private static String ledgerInsert(boolean withRoot) {
        return "INSERT INTO dead_letter_records (" + (withRoot ? "root_record_id, " : "")
                + "cluster_id, topic_generation, origin_topic, origin_partition, origin_offset, "
                + "failed_consumer_group, origin_kind, status, occurred_at) VALUES ("
                + (withRoot ? "1, " : "")
                + "'contract-fixture', 1, 'contract.fixture', 0, 1, 'contract-group', 'RESOLVED_ORIGIN', 'OPEN', "
                + "CURRENT_TIMESTAMP(6))";
    }

    private static String outboxInsert(boolean withKind) {
        return "INSERT INTO outbox_events (aggregate_type, aggregate_id, event_type, event_id, payload, created_at"
                + (withKind ? ", record_kind" : "") + ") VALUES ('CONTRACT', 'contract-1', 'contract.fixture', "
                + "'contract-fixture-event', '{}', CURRENT_TIMESTAMP(6)" + (withKind ? ", 'DOMAIN'" : "") + ")";
    }

    private static int mysqlCode(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                return sql.getErrorCode();
            }
        }
        return -1;
    }
}
