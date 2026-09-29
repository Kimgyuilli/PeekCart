package com.peekcart.global.outbox;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxPollingScheduler {

    private final OutboxPollingService outboxPollingService;

    // 서비스별 락 이름(app.outbox.lock-name, 예: productOutboxPollingJob).
    // ShedLock 이 @SchedulerLock name 의 ${} placeholder 를 resolve 한다.
    // 주기를 설정으로 뺀 이유는 테스트가 **자기가 측정하는 사이클을 통제**해야 하기 때문이다.
    // 배경 잡이 같은 행을 동시에 집어가면 "몇 번 발행됐나" 를 세는 테스트가 스케줄러 타이밍에 흔들린다.
    // 운영 기본값은 5s 로 불변이다.
    @Scheduled(fixedDelayString = "${app.outbox.polling.delay:5s}")
    @SchedulerLock(name = "${app.outbox.lock-name:outboxPollingJob}", lockAtMostFor = "PT5M", lockAtLeastFor = "PT4S")
    public void pollAndPublish() {
        outboxPollingService.pollAndPublish();
    }
}
