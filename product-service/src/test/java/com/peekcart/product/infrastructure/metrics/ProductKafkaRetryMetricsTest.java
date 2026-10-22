package com.peekcart.product.infrastructure.metrics;

import com.peekcart.product.domain.model.Inventory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ProductKafkaRetryMetrics 단위 테스트")
class ProductKafkaRetryMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProductKafkaRetryMetrics metrics = new ProductKafkaRetryMetrics(registry);
    private final ConsumerRecord<String, String> record = new ConsumerRecord<>("order.created", 0, 0L, "k", "v");

    @Test
    @DisplayName("listener 예외로 감싼 낙관락 충돌은 cause=optimistic_lock 으로 센다")
    void wrappedOptimisticLockIsClassified() {
        Exception wrapped = new ListenerExecutionFailedException("listener failed",
                new ObjectOptimisticLockingFailureException(Inventory.class, 1L));

        metrics.failedDelivery(record, wrapped, 1);
        metrics.failedDelivery(record, wrapped, 2);

        assertThat(failed("optimistic_lock")).isEqualTo(2.0);
        assertThat(failed("other")).isZero();
    }

    @Test
    @DisplayName("낙관락이 아닌 실패는 cause=other 로 센다 — 충돌률을 부풀리지 않는다")
    void otherFailureIsNotCountedAsConflict() {
        metrics.failedDelivery(record, new ListenerExecutionFailedException("listener failed",
                new IllegalStateException("boom")), 1);

        assertThat(failed("other")).isEqualTo(1.0);
        assertThat(failed("optimistic_lock")).isZero();
    }

    @Test
    @DisplayName("recoverer 성공은 topic 별로 따로 센다")
    void recoveredIsCountedPerTopic() {
        metrics.recovered(record, new IllegalStateException("exhausted"));

        assertThat(registry.find(ProductKafkaRetryMetrics.RECOVERED).tag("topic", "order.created")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find(ProductKafkaRetryMetrics.DELIVERY_FAILED).counters()).isEmpty();
    }

    private double failed(String cause) {
        var counter = registry.find(ProductKafkaRetryMetrics.DELIVERY_FAILED)
                .tag("topic", "order.created").tag("cause", cause).counter();
        return counter == null ? 0 : counter.count();
    }
}
