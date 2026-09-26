package com.peekcart.global.idempotency;

import com.peekcart.global.retention.IdempotencyRetentionProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import com.peekcart.support.SharedContainers;
import com.peekcart.support.TestRsaKeys;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 서비스×잡 매트릭스 검증 — user (ADR-0012 D5 · 구현 ② PR3).
 * <p>user 는 processed_events/outbox_events 를 소유하지 않는다 → cleanup 잡 0.
 * 잡 클래스가 물리적으로 없어 bean 이 없고, retention properties 도 @EnableConfigurationProperties
 * 미활성이라 bean 화되지 않는다(common 클래스가 있어도 user 로 누출 안 됨 — @ConfigurationPropertiesScan 부재).
 * <p>User 는 Kafka 미사용(UserApplication 이 KafkaAutoConfiguration 제외). 공유 Kafka 컨테이너는
 * 모듈 싱글톤이라 함께 뜨지만 이 context 는 연결하지 않는다.
 */
@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration"
})
@DisplayName("서비스×잡 매트릭스 — user (cleanup 잡 0)")
class UserCleanupMatrixIntegrationTest {

    /** 개인키 커밋 금지(ADR-0013 D2) — 런타임 생성 키쌍으로 서명/검증 키를 주입한다. */
    @DynamicPropertySource
    static void jwtKeys(DynamicPropertyRegistry registry) {
        TestRsaKeys.register(registry);
    }

    @Autowired ApplicationContext ctx;

    @Test
    @DisplayName("user 는 cleanup 잡 bean 도 retention properties bean 도 갖지 않는다")
    void matrix_userHasNoCleanupJobs() {
        assertThat(ctx.containsBean("processedEventCleanupScheduler")).isFalse();
        assertThat(ctx.containsBean("outboxEventCleanupScheduler")).isFalse();
        // common 에 클래스는 있으나 user 는 @EnableConfigurationProperties 안 함 → bean 없음(누출 방지 회귀).
        assertThat(ctx.getBeanNamesForType(IdempotencyRetentionProperties.class)).isEmpty();
    }
}
