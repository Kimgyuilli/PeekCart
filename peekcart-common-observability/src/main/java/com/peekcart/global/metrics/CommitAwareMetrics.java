package com.peekcart.global.metrics;

import io.micrometer.core.instrument.Counter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 트랜잭션이 <b>커밋된 뒤에만</b> 카운터를 올린다.
 *
 * <p><b>왜 필요한가</b>: saga 계측은 전부 {@code @Transactional} 메서드 안에서 일어난다.
 * 메서드 본문에서 바로 증가시키면 이후 flush/commit 이 실패했을 때 <b>DB·Outbox 는 롤백되는데
 * Micrometer 카운터만 남는다</b>. 그러면 메트릭이 실제 사건 수를 부풀리고, "실제 전이가 일어났을
 * 때만 올린다" 는 계약이 깨진다 — 그 계약이 alert 임계값의 근거다.
 *
 * <p>트랜잭션이 없으면(스케줄러 바깥 호출 등) 즉시 증가시킨다. 커밋을 기다릴 대상이 없기 때문이다.
 *
 * <p>롤백 시에는 {@link TransactionSynchronization#afterCommit()} 가 호출되지 않으므로 증가가
 * 일어나지 않는다 — 그게 이 클래스의 전부다.
 *
 * <p><b>계측 실패를 호출자에게 전파하지 않는다.</b> {@code afterCommit} 시점에는 DB 트랜잭션이
 * 이미 커밋돼 되돌릴 수 없는데, 여기서 예외가 나가면 호출자는 커밋 실패로 받는다. Kafka listener
 * 안이라면 이미 커밋된 이벤트를 재처리하게 된다 — <b>관측성 실패가 비즈니스 처리 결과를 바꾸는 것</b>은
 * 어떤 메트릭 값보다도 나쁘다. 그래서 잡아서 로그만 남기고 메트릭만 유실시킨다.
 */
public final class CommitAwareMetrics {

    private static final Logger log = LoggerFactory.getLogger(CommitAwareMetrics.class);

    private CommitAwareMetrics() {
    }

    public static void increment(Counter counter) {
        increment(counter, 1.0);
    }

    public static void increment(Counter counter, double amount) {
        if (amount <= 0) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            counter.increment(amount);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    counter.increment(amount);
                } catch (Exception e) {
                    // 커밋은 이미 끝났다. 여기서 던지면 호출자가 커밋 실패로 오인한다.
                    // 태그와 수량까지 남긴다 — 이 메트릭들은 태그별 시계열이고 amount 가 사건
                    // 건수(sweeper 회수·타임아웃 취소)라, 이름만으로는 무엇이 몇 건 유실됐는지 모른다.
                    log.warn("메트릭 증가 실패 — 커밋은 유지되고 메트릭만 유실된다. meter={}, amount={}",
                            counter.getId(), amount, e);
                }
            }
        });
    }
}
