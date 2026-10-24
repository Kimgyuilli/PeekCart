package com.peekcart.global.outbox;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@EnableConfigurationProperties(OutboxPollingProperties.class)
public class OutboxPollingScheduler {

    /**
     * 한 실행이 lock 을 쥘 수 있는 상한. {@code cycle-timeout}(4m) 에 이벤트 하나의 최장 발행 시간을 더한 것보다
     * 길어야 한다 — 짧으면 살아있는 실행의 lock 이 풀려 다른 인스턴스가 같은 PENDING 을 동시에 발행한다.
     * {@link OutboxPollingProperties} 가 최소 보존과 이 값의 순서를 부팅 시 검사한다.
     */
    static final String LOCK_AT_MOST_FOR = "PT5M";

    private final OutboxPollingService outboxPollingService;

    // 서비스별 락 이름(app.outbox.lock-name, 예: productOutboxPollingJob).
    // ShedLock 이 @SchedulerLock name 의 ${} placeholder 를 resolve 한다.
    // 주기를 설정으로 뺀 이유는 테스트가 **자기가 측정하는 사이클을 통제**해야 하기 때문이다.
    // 배경 잡이 같은 행을 동시에 집어가면 "몇 번 발행됐나" 를 세는 테스트가 스케줄러 타이밍에 흔들린다.
    // 주기와 최소 보존의 값은 서비스 base yml 이 소유한다(ADR-0045). **인라인 기본값을 두지 않는다** —
    // 기본값이 있으면 키가 빠져도 조용히 돌고, 처리율을 바꾸려는 사람은 효과 없는 곳을 고친다.
    // 두 값의 검사는 OutboxPollingProperties 가 부팅 시점에 한다.
    @Scheduled(fixedDelayString = "${app.outbox.polling.delay}")
    @SchedulerLock(name = "${app.outbox.lock-name:outboxPollingJob}", lockAtMostFor = LOCK_AT_MOST_FOR,
            lockAtLeastFor = "${app.outbox.polling.lock-at-least-for}")
    public void pollAndPublish() {
        outboxPollingService.pollAndPublish();
    }
}
