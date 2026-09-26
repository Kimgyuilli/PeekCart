package com.peekcart.global.outbox;

import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * outbox_events cleanup 통합 테스트 (ADR-0012 D5 · 구현 ② PR3).
 * <p>PUBLISHED + published_at 경과분만 삭제하고 PENDING·FAILED·published_at IS NULL·미만료 는 보존한다
 * (미발행/실패 유실 금지). ShedLock 때문에 {@code scheduler.cleanup()} 은 클래스당 1회만 호출한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 소량 데이터로 다중 batch 를 강제 (outbox 도 배치 계약 공유 — cutoff 1회·batch 반복).
        "app.idempotency.cleanup.batch-size=2"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("outbox_events cleanup 통합 테스트 (PUBLISHED 경과분만 삭제)")
class OutboxEventCleanupIntegrationTest extends AbstractIntegrationTest {

    @Autowired OutboxEventCleanupScheduler scheduler;
    @Autowired OutboxEventJpaRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    private void insertOutbox(String status, LocalDateTime createdAt, LocalDateTime publishedAt) {
        jdbc.update("INSERT INTO outbox_events "
                        + "(aggregate_type, aggregate_id, event_type, event_id, payload, status, retry_count, created_at, published_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                "PRODUCT", "p1", "product.test.probe", UUID.randomUUID().toString(), "{}",
                status, 0, Timestamp.valueOf(createdAt),
                publishedAt == null ? null : Timestamp.valueOf(publishedAt));
    }

    @Test
    @DisplayName("cleanup() 은 PUBLISHED+경과분을 다중 batch 로 삭제하고 PENDING/FAILED/NULL/미만료는 보존한다")
    void cleanup_deletesOnlyPublishedExpired_acrossBatches() {
        LocalDateTime now = LocalDateTime.now();
        // PUBLISHED+경과 5건 (batch-size=2 → 3 batch) → 전부 삭제 대상.
        for (int i = 0; i < 5; i++) {
            insertOutbox("PUBLISHED", now.minusDays(10), now.minusDays(10));
        }
        insertOutbox("PUBLISHED", now.minusDays(1), now.minusDays(1));   // 미만료 → 보존
        insertOutbox("PENDING", now.minusDays(10), null);                // 미발행 → 보존
        insertOutbox("FAILED", now.minusDays(10), now.minusDays(10));    // 실패(status 가드) → 보존
        insertOutbox("PUBLISHED", now.minusDays(10), null);              // published_at NULL → 보존
        assertThat(repository.count()).isEqualTo(9);

        scheduler.cleanup();

        // PUBLISHED+경과 5건만 삭제(다중 batch) → 4건 보존.
        assertThat(repository.count()).isEqualTo(4);
        assertThat(repository.countByStatus(OutboxEventStatus.PENDING)).isEqualTo(1);
        assertThat(repository.countByStatus(OutboxEventStatus.FAILED)).isEqualTo(1);
        assertThat(repository.countByStatus(OutboxEventStatus.PUBLISHED)).isEqualTo(2); // recent + null-published_at
    }
}
