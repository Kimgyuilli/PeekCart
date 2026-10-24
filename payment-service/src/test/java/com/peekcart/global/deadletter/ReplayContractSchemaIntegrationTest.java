package com.peekcart.global.deadletter;

import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
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
 * 증명된다. 본문은 order-service 의 같은 테스트와 같다(가드 발견 음성 대조는 order 에만 있다).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("payment-service replay 판별자 NOT NULL contract")
class ReplayContractSchemaIntegrationTest extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

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
    @DisplayName("root_record_id 를 빠뜨린 원장 INSERT 와 NULL 로 되돌리는 UPDATE 는 거부되고 값을 넣으면 들어간다")
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
    @DisplayName("record_kind 를 빠뜨린 outbox INSERT 는 거부되고 값을 넣으면 들어간다")
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

    private Map<String, Object> column(String table, String column) {
        return jdbc.queryForMap("""
                SELECT IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
                 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?
                """, table, column);
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
