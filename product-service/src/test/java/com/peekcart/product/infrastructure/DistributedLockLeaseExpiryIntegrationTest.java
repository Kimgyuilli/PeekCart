package com.peekcart.product.infrastructure;

import com.peekcart.global.lock.DistributedLockManager;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;

/**
 * D-025 P4 — {@code lease} 만료가 락을 <b>조용히</b> 무력화한다.
 *
 * <p>{@code InventoryLockFacade} 는 lease 를 5초 고정으로 잡는다. 트랜잭션이 그보다 길어지면
 * 락은 만료로 풀리고, 그 뒤의 {@code unlock} 은 {@code isHeldByCurrentThread()} 가 false 라
 * <b>아무 일도 하지 않고 통과</b>한다 — 예외도, 로그도, 지표도 남지 않는다. 즉 "락을 잡고 있다고
 * 믿는 구간"과 "실제로 잡고 있는 구간"이 어긋나도 그 사실이 어디에도 드러나지 않는다.
 *
 * <p>이 테스트는 프로덕션 상수를 건드리지 않고 {@link DistributedLockManager} 를 <b>직접</b>
 * 짧은 lease 로 호출해 그 성질만 고정한다.
 *
 * <p><b>소유처</b>: {@code DistributedLockManager} 는 {@code :common} 에 있으나 실 소비자는
 * product-service 단 하나다({@code InventoryLockFacade}). {@code :common} 의 {@code src/test} 에는
 * testcontainers 가 없고(단위 테스트 전용 소스셋) Redis 컨테이너 패턴은 여기에 있으므로 여기에 둔다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import(SharedContainers.class)
@DisplayName("D-025 P4 — 분산 락 lease 만료는 조용히 통과한다")
class DistributedLockLeaseExpiryIntegrationTest {

    private static final String LOCK_KEY = "d025-lease-expiry-probe";
    private static final long LEASE_SECONDS = 1;

    @Autowired
    DistributedLockManager lockManager;

    @Test
    @DisplayName("lease 가 만료되면 보유자가 모르는 채로 남이 같은 키를 잡고, 보유자의 unlock 은 no-op 으로 통과한다")
    void leaseExpiry_releasesLockSilently() throws Exception {
        assertThat(lockManager.tryLock(LOCK_KEY, 0, LEASE_SECONDS, TimeUnit.SECONDS))
                .as("보유자가 락을 잡는다")
                .isTrue();

        // Redisson 의 소유권은 스레드 단위이고 재진입을 허용한다. 그래서 "탈취자" 와 "제3 관찰자" 는
        // 반드시 서로 다른 스레드여야 한다 — 같은 스레드로 재확인하면 재진입이라 항상 true 가 나온다.
        ExecutorService thief = Executors.newSingleThreadExecutor();
        ExecutorService probe = Executors.newSingleThreadExecutor();
        try {
            // (a) lease 만료 후, 보유자가 여전히 "구간 안"이라고 믿는 동안 남이 같은 키를 잡는다.
            await().atMost(Duration.ofSeconds(LEASE_SECONDS + 5))
                    .pollInterval(Duration.ofMillis(200))
                    .until(tryLockOn(thief, LEASE_SECONDS + 10));

            // (b) 그리고 보유자의 unlock 은 예외 없이 통과한다 — 만료됐다는 신호가 어디에도 없다.
            assertThatCode(() -> lockManager.unlock(LOCK_KEY))
                    .as("만료된 락의 해제는 조용히 no-op — 이것이 D-025 의 관측 공백")
                    .doesNotThrowAnyException();

            // (c) 그 no-op 이 탈취자의 락을 빼앗지도 않는다(isHeldByCurrentThread 가드는 제 일을 한다).
            assertThat(tryLockOn(probe, LEASE_SECONDS).call())
                    .as("제3 스레드는 획득 실패 — 보유자의 unlock 이 탈취자의 락을 풀지 않았다")
                    .isFalse();
        } finally {
            thief.shutdownNow();
            probe.shutdownNow();
        }
    }

    /** 지정한 스레드에서 같은 키의 락 획득을 시도한다 — 대기 없이(waitTime=0) 즉시 판정한다. */
    private Callable<Boolean> tryLockOn(ExecutorService executor, long leaseSeconds) {
        return () -> {
            Future<Boolean> acquired =
                    executor.submit(() -> lockManager.tryLock(LOCK_KEY, 0, leaseSeconds, TimeUnit.SECONDS));
            return acquired.get();
        };
    }
}
