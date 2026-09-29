package com.peekcart.global.idempotency;

import com.peekcart.global.retention.IdempotencyRetentionProperties;
import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * processed_events retention cleanup 스케줄러 (ADR-0012 D5 · 구현 ② PR3).
 *
 * <p>보존기간({@code app.idempotency.retention}) 경과 행을 배치로 삭제한다. 보존기간 하한(floor)은
 * {@link IdempotencyRetentionProperties} 의 cross-field 검증(@AssertTrue)으로 부팅 시 강제되므로
 * 이 잡이 멱등성 창을 침범할 수 없다.
 *
 * <p><b>배치 삭제 계약</b>: unbounded 단일 DELETE 가 큰 테이블에서 장기 락·긴 트랜잭션을 유발하지 않도록
 * {@code cutoff} 을 실행 시작 시 1회 계산하고, 작은 batch({@code cleanup.batch-size})를
 * {@code max-batches-per-run} 회까지 반복 삭제한다. 각 batch 는 리포지토리 메서드의 독립 트랜잭션이다.
 *
 * <p>소유 서비스(product/order/payment/notification)에만 물리 배치된다(구현 ② PR3 매트릭스).
 * 스키마 격리로 서비스마다 잡 인스턴스가 독립하므로 ShedLock lock-name 은 잡별 상수를 쓴다.
 */
@Component
@EnableConfigurationProperties(IdempotencyRetentionProperties.class)
@RequiredArgsConstructor
public class ProcessedEventCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(ProcessedEventCleanupScheduler.class);

    private final ProcessedEventJpaRepository repository;
    private final IdempotencyRetentionProperties properties;

    @Scheduled(cron = "${app.idempotency.cleanup.cron:0 30 3 * * *}")
    @SchedulerLock(name = "processedEventsCleanupJob", lockAtMostFor = "PT10M", lockAtLeastFor = "PT1M")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minus(properties.getRetention());
        int batchSize = properties.getCleanup().getBatchSize();
        int maxBatches = properties.getCleanup().getMaxBatchesPerRun();

        long total = 0;
        for (int i = 0; i < maxBatches; i++) {
            int deleted = repository.deleteBatchOlderThan(cutoff, batchSize);
            total += deleted;
            if (deleted < batchSize) {
                break;
            }
        }
        if (total > 0) {
            log.info("processed_events cleanup: {}건 삭제 (cutoff={})", total, cutoff);
        }
    }
}
