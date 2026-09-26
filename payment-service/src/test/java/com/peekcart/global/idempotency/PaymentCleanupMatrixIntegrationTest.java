package com.peekcart.global.idempotency;

import com.peekcart.global.outbox.OutboxEventCleanupScheduler;
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
 * 서비스×잡 매트릭스 검증 — payment (ADR-0012 D5 · 구현 ② PR3).
 * payment 는 발행 서비스 → processed + outbox cleanup bean 둘 다 소유.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@Import({IntegrationTestConfig.class, SharedContainers.class})
@DisplayName("서비스×잡 매트릭스 — payment (processed + outbox)")
class PaymentCleanupMatrixIntegrationTest {

    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("payment 는 processed + outbox cleanup bean 둘 다 소유")
    void matrix_paymentOwnsBothCleanupJobs() {
        assertThat(ctx.getBeanNamesForType(ProcessedEventCleanupScheduler.class)).hasSize(1);
        assertThat(ctx.getBeanNamesForType(OutboxEventCleanupScheduler.class)).hasSize(1);
    }
}
