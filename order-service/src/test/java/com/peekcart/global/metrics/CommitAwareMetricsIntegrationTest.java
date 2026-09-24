package com.peekcart.global.metrics;

import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link CommitAwareMetrics} 계약 (구현 ④-d-1 diff 리뷰 1R #1 · 2R #2·#4).
 *
 * <p><b>이 테스트가 없으면 구현을 즉시 증가 방식으로 되돌려도 전부 통과한다.</b> 서비스 통합테스트는
 * 프록시가 실제로 커밋한 뒤 값을 읽으므로 양성 경로만 보고, "롤백이면 증가 0" 은 고정되지 않는다.
 *
 * <p>실제 트랜잭션 매니저로 전파 속성별 의미를 함께 못박는다 —
 * {@code REQUIRES_NEW} 는 자기 커밋으로 확정되고 바깥 롤백에 끌려가지 않는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("CommitAwareMetrics — 커밋 이후 계측 계약")
class CommitAwareMetricsIntegrationTest extends AbstractIntegrationTest {

    @Autowired PlatformTransactionManager transactionManager;

    private MeterRegistry registry;
    private Counter counter;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        counter = Counter.builder("test.commit.aware").register(registry);
    }

    @Test
    @DisplayName("트랜잭션이 없으면 즉시 증가한다 — 기다릴 커밋이 없다")
    void incrementsImmediatelyWithoutTransaction() {
        CommitAwareMetrics.increment(counter);

        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("커밋 전에는 0, 커밋 후에 증가한다")
    void incrementsOnlyAfterCommit() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            CommitAwareMetrics.increment(counter);
            // 아직 커밋 전 — 여기서 올라가면 롤백 시 카운터만 남는다
            assertThat(counter.count()).isZero();
        });

        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("롤백이면 증가 0 — 이 클래스의 존재 이유다")
    void doesNotIncrementOnRollback() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> {
            CommitAwareMetrics.increment(counter);
            status.setRollbackOnly();
        });

        assertThat(counter.count()).isZero();
    }

    @Test
    @DisplayName("예외로 롤백돼도 증가 0")
    void doesNotIncrementWhenExceptionRollsBack() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThatCode(() -> template.executeWithoutResult(status -> {
            CommitAwareMetrics.increment(counter);
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(counter.count()).isZero();
    }

    @Test
    @DisplayName("REQUIRES_NEW 는 자기 커밋으로 확정되고 바깥 롤백에 끌려가지 않는다")
    void requiresNewIsIndependentOfOuterRollback() {
        TransactionTemplate outer = new TransactionTemplate(transactionManager);
        TransactionTemplate inner = new TransactionTemplate(transactionManager);
        inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        outer.executeWithoutResult(outerStatus -> {
            inner.executeWithoutResult(innerStatus -> CommitAwareMetrics.increment(counter));
            // 안쪽이 이미 커밋됐다 → 이미 증가해 있어야 한다
            assertThat(counter.count()).isEqualTo(1.0);
            outerStatus.setRollbackOnly();
        });

        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("증가량 0 이하는 호출 자체를 무시한다 — 동기화를 등록하지 않는다")
    void ignoresNonPositiveAmount() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        template.executeWithoutResult(status -> CommitAwareMetrics.increment(counter, 0));

        assertThat(counter.count()).isZero();
    }

    @Test
    @DisplayName("계측 실패가 커밋을 실패로 만들지 않는다 — 관측성이 비즈니스 결과를 바꾸면 안 된다")
    void meterFailureDoesNotPropagate() {
        Counter exploding = new ExplodingCounter(counter);
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThatCode(() -> template.executeWithoutResult(
                status -> CommitAwareMetrics.increment(exploding))).doesNotThrowAnyException();
    }

    /** {@code increment} 가 터지는 카운터. afterCommit 예외가 호출자에게 새는지 보기 위한 것. */
    private static final class ExplodingCounter implements Counter {
        private final Counter delegate;

        private ExplodingCounter(Counter delegate) {
            this.delegate = delegate;
        }

        @Override
        public void increment(double amount) {
            throw new IllegalStateException("meter registry down");
        }

        @Override
        public double count() {
            return delegate.count();
        }

        @Override
        public Id getId() {
            return delegate.getId();
        }
    }
}
