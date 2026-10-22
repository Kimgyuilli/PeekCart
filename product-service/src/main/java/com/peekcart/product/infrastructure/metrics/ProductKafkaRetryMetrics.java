package com.peekcart.product.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.hibernate.StaleStateException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.kafka.listener.RetryListener;
import org.springframework.stereotype.Component;

/**
 * product-service 소비 재시도 메트릭 (D-078 ⑤ — ADR-0025 비관적 락 승격 게이트의 입력).
 *
 * <p>낙관락 충돌은 <b>실패한 뒤에만</b> 드러난다(ADR-0025 Consequences). 그래서 충돌률은 재시도
 * 경로에서만 셀 수 있다. 이 계측이 없을 때는 Pod 로그를 모아 세어야 했고, replicas 가 늘면
 * Pod 하나만 놓쳐도 수치가 틀린다.
 *
 * <ul>
 *   <li>{@code kafka.listener.delivery.failed} — 실패한 전달 1회마다. 마지막 시도도 포함한다.
 *       {@code cause} 는 낙관락 여부만 가른다 — 예외 클래스명을 태그로 쓰면 cardinality 가 열린다.</li>
 *   <li>{@code kafka.listener.recovered} — 재시도를 소진하고 recoverer(DLQ 발행)가 성공한 건.</li>
 * </ul>
 *
 * <p>트랜잭션 밖에서 호출되므로 {@code CommitAwareMetrics} 를 쓰지 않는다 — 실패는 이미 롤백된 뒤다.
 */
@Component
public class ProductKafkaRetryMetrics implements RetryListener {

    static final String DELIVERY_FAILED = "kafka.listener.delivery.failed";
    static final String RECOVERED = "kafka.listener.recovered";

    private final MeterRegistry registry;

    public ProductKafkaRetryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void failedDelivery(ConsumerRecord<?, ?> record, Exception ex, int deliveryAttempt) {
        Counter.builder(DELIVERY_FAILED)
                .tag("topic", record.topic())
                .tag("cause", isOptimisticLock(ex) ? "optimistic_lock" : "other")
                .description("소비 전달 실패 (재시도 포함, 마지막 시도 포함)")
                .register(registry)
                .increment();
    }

    @Override
    public void recovered(ConsumerRecord<?, ?> record, Exception ex) {
        Counter.builder(RECOVERED)
                .tag("topic", record.topic())
                .description("재시도 소진 후 recoverer(DLQ 발행) 성공")
                .register(registry)
                .increment();
    }

    private static boolean isOptimisticLock(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof OptimisticLockingFailureException
                    || cause instanceof jakarta.persistence.OptimisticLockException
                    || cause instanceof StaleStateException) {
                return true;
            }
        }
        return false;
    }
}
