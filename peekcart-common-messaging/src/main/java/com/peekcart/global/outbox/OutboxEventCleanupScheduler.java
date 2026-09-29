package com.peekcart.global.outbox;

import com.peekcart.global.retention.IdempotencyRetentionProperties;
import com.peekcart.global.retention.OutboxRetentionProperties;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * outbox_events cleanup 스케줄러 (ADR-0012 D5 · 구현 ② PR3).
 *
 * <p>PUBLISHED 상태 + {@code published_at} 이 {@code app.outbox.retention} 경과한 행만 배치 삭제한다.
 * PENDING·FAILED·{@code published_at IS NULL} 행은 보존한다(미발행/실패 유실 금지). 배치 삭제 계약은
 * processed cleanup 과 동일({@link IdempotencyRetentionProperties.Cleanup} 공유 — {@code cutoff} 1회 계산 후
 * 작은 batch 반복).
 *
 * <p>outbox 를 소유한 4서비스(order/product/payment/notification)에 물리 배치된다. notification 은
 * 도메인 이벤트를 발행하지 않지만 DLQ replay 재발행 주체라 같은 테이블·잡을 갖는다(ADR-0020 D2 · ④-c-2b-2 P9).
 * user-service 는 Kafka 소비/발행이 없어 제외된다.
 */
@Component
@EnableConfigurationProperties({OutboxRetentionProperties.class, IdempotencyRetentionProperties.class})
@RequiredArgsConstructor
public class OutboxEventCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxEventCleanupScheduler.class);

    private final OutboxEventJpaRepository repository;
    private final OutboxRetentionProperties outboxProperties;
    private final IdempotencyRetentionProperties idempotencyProperties;

    @Scheduled(cron = "${app.outbox.cleanup.cron:0 45 3 * * *}")
    @SchedulerLock(name = "outboxEventsCleanupJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minus(outboxProperties.getRetention());
        int batchSize = idempotencyProperties.getCleanup().getBatchSize();
        int maxBatches = idempotencyProperties.getCleanup().getMaxBatchesPerRun();

        long total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = repository.deletePublishedBatchOlderThan(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("outbox_events cleanup: PUBLISHED {}건 삭제 (cutoff={})", total, cutoff);
        }
    }
}
