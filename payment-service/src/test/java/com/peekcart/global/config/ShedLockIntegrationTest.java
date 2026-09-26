package com.peekcart.global.config;

import com.peekcart.support.AbstractIntegrationTest;
import com.peekcart.support.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;

@SpringBootTest
@Import(SharedContainers.class)
@TestPropertySource(properties = {
        "spring.task.scheduling.pool.size=1",
        "toss.payments.secret-key=test_sk_fake",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        // ADR-0029: 스케줄러는 테스트에서 기본 off 다. 실제 타이머 발화로 ShedLock 락 기록을 확인하는 것이 검증 대상이다.
        "app.scheduling.enabled=true"
})
// 켠 자율 writer 의 수명을 자기 클래스에 가둔다 (ADR-0029 D3) — context 가 캐시된 채 남으면
// 스케줄러가 이후 클래스의 공유 브로커·DB 를 계속 고친다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("ShedLock 통합 테스트")
class ShedLockIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("shedlock 테이블이 Flyway로 생성되고, 스케줄러 실행 시 락 레코드가 기록된다")
    void shedlockTableExistsAndLockRecordCreated() {
        // shedlock 테이블 존재 확인
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'shedlock'",
                Integer.class);
        assertThat(tableCount).isEqualTo(1);

        // 스케줄러가 실행되어 shedlock 테이블에 락 레코드가 생성될 때까지 대기
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Integer lockCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM shedlock", Integer.class);
                    assertThat(lockCount).isGreaterThanOrEqualTo(1);
                });
    }

    @Test
    @DisplayName("paymentOutboxPollingJob 락 레코드가 생성된다 (Payment peel: 공유 DB poller 소유권 분리로 payment-service 락 이름)")
    void outboxPollingJobLockRecordCreated() {
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofSeconds(1))
                .untilAsserted(() -> {
                    Integer count = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM shedlock WHERE name = 'paymentOutboxPollingJob'",
                            Integer.class);
                    assertThat(count).isEqualTo(1);
                });
    }
}
