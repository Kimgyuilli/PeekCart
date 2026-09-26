package com.peekcart.global.idempotency;

import com.peekcart.global.outbox.OutboxEventCleanupScheduler;
import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * processed_events retention cleanup 통합 테스트 (ADR-0012 D5 · 구현 ② PR3).
 * <p>보존기간(7d) 경과 행 배치 삭제 / 미만료 보존 / batch LIMIT / 서비스×잡 매트릭스(product=둘 다 소유).
 * <p>ShedLock(PROXY_METHOD·lockAtLeastFor=1m) 때문에 {@code scheduler.cleanup()} 은 클래스당 1회만 호출한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // 소량 데이터로 다중 batch 를 강제하기 위해 batch-size 축소.
        "app.idempotency.cleanup.batch-size=5"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("processed_events retention cleanup 통합 테스트")
class ProcessedEventCleanupIntegrationTest extends AbstractIntegrationTest {

    @Autowired ProcessedEventCleanupScheduler scheduler;
    @Autowired ProcessedEventJpaRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired ApplicationContext ctx;

    @BeforeEach
    void setUp() {
        cleanDatabase();
    }

    private void insertProcessed(LocalDateTime processedAt) {
        jdbc.update("INSERT INTO processed_events (event_id, consumer_group, processed_at) VALUES (?, ?, ?)",
                UUID.randomUUID().toString(), "test-group", Timestamp.valueOf(processedAt));
    }

    @Test
    @DisplayName("cleanup() 은 보존기간 경과 행을 다중 batch 로 전부 삭제하고 미만료 행은 보존한다")
    void cleanup_deletesExpiredAcrossBatches_keepsRecent() {
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < 12; i++) {
            insertProcessed(now.minusDays(10)); // < cutoff(now-7d) → 삭제 대상 (batch-size=5 → 3 batch)
        }
        for (int i = 0; i < 3; i++) {
            insertProcessed(now.minusDays(1));  // > cutoff → 보존
        }
        assertThat(repository.count()).isEqualTo(15);

        scheduler.cleanup();

        assertThat(repository.count()).isEqualTo(3); // 미만료 3건만 남음
    }

    @Test
    @DisplayName("deleteBatchOlderThan 은 LIMIT 를 준수한다 (매칭 > limit 이면 정확히 limit 삭제)")
    void deleteBatch_respectsLimit() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        for (int i = 0; i < 10; i++) {
            insertProcessed(LocalDateTime.now().minusDays(10));
        }

        int deleted = repository.deleteBatchOlderThan(cutoff, 4);

        assertThat(deleted).isEqualTo(4);
        assertThat(repository.count()).isEqualTo(6);
    }

    @Test
    @DisplayName("서비스×잡 매트릭스: product 는 processed + outbox cleanup bean 둘 다 소유")
    void matrix_productOwnsBothCleanupJobs() {
        assertThat(ctx.getBeanNamesForType(ProcessedEventCleanupScheduler.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(OutboxEventCleanupScheduler.class)).hasSize(1);
    }
}
