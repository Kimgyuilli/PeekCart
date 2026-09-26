package com.peekcart.global.idempotency;

import com.peekcart.global.deadletter.DeadLetterPublicationReconciler;
import com.peekcart.global.outbox.OutboxEventCleanupScheduler;
import com.peekcart.global.outbox.OutboxPollingScheduler;
import com.peekcart.support.IntegrationTestConfig;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서비스×잡 매트릭스 검증 — notification (ADR-0012 D5 · 구현 ② PR3 · ADR-0020 D2 로 갱신).
 *
 * <p><b>이 매트릭스는 ④-c-2b-2 에서 바뀌었다.</b> 구현 ② PR3 시점의 계약은 "notification 은 소비
 * 전용이므로 outbox cleanup 이 없다" 였으나, ADR-0020 §D2 가 notification 에 {@code outbox_events} 를
 * 신설했다 — DLQ replay 는 <b>원장 소유 서비스가 자기 원장 행을 재발행</b>하는 것이고(§D8-3),
 * notification 도 자기 원장을 갖기 때문이다. 따라서 이제 <b>두 cleanup 이 모두</b> 떠야 한다.
 *
 * <p>(user 서비스의 "잡 0" 은 클래스 물리 부재로 구조적으로 보장되며 여기서 바뀌지 않았다.)
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("서비스×잡 매트릭스 — notification (processed + outbox cleanup)")
class NotificationCleanupMatrixIntegrationTest {

    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("processed cleanup 과 outbox cleanup 이 모두 bean 으로 뜬다")
    void matrix_notificationHasBothCleanups() {
        assertThat(ctx.getBeanNamesForType(ProcessedEventCleanupScheduler.class)).hasSize(1);
        // ④-c-2b-2 P9 이전에는 클래스 자체가 없어 bean 부재가 계약이었다(구현 ② PR3).
        assertThat(ctx.getBeanNamesForType(OutboxEventCleanupScheduler.class)).hasSize(1);
    }

    @Test
    @DisplayName("발행 표면도 함께 뜬다 — poller 와 발행 축 reconciler")
    void matrix_notificationHasPublicationSurface() {
        // cleanup 만 보면 "테이블은 있는데 아무도 발행하지 않는" 상태가 통과한다.
        assertThat(ctx.getBeanNamesForType(OutboxPollingScheduler.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(DeadLetterPublicationReconciler.class)).hasSize(1);
    }
}
