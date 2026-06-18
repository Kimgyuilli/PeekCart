package com.peekcart.global.outbox;

import lombok.RequiredArgsConstructor;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class OutboxPollingScheduler {

    private final OutboxPollingService outboxPollingService;

    // 공유 DB 전환기 소유권 분리: 앱별 락 이름(root=rootOutboxPollingJob / product=productOutboxPollingJob).
    // ShedLock 이 @SchedulerLock name 의 ${} placeholder 를 resolve 한다.
    @Scheduled(fixedDelay = 5000)
    @SchedulerLock(name = "${app.outbox.lock-name:outboxPollingJob}", lockAtMostFor = "PT5M", lockAtLeastFor = "PT4S")
    public void pollAndPublish() {
        outboxPollingService.pollAndPublish();
    }
}
